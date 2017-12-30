package at.forsyte.apalache.tla.bmcmt.rules

import at.forsyte.apalache.tla.bmcmt._
import at.forsyte.apalache.tla.bmcmt.implicitConversions._
import at.forsyte.apalache.tla.bmcmt.types._
import at.forsyte.apalache.tla.lir.TlaEx
import at.forsyte.apalache.tla.lir.convenience.tla

/**
  * Rewriting for the syntactic form PICK _: tau FROM ... and FUN-MERGE.
  * Importantly, the user picks the type tau somewhat arbitrary, and a new cell is assigned type tau.
  * So, it is up to the user to ensure that the cells in the set have types compatible with tau.
  *
  * TODO: check for empty sets, statically and dynamically
  *
  * @author Igor Konnov
  */
class PickFromAndFunMerge(rewriter: SymbStateRewriter) {
  /**
    * Determine the general type of the set elements and pick an element of this type.
    *
    * @param set a set cell
    * @param state a symbolic state
    * @return a new symbolic state whose expression stores a fresh cell that corresponds to the picked element.
    */
  def pick(set: ArenaCell, state: SymbState): SymbState = {
    set.cellType match {
      case FinSetT(ConstT()) =>
        pickBasic(ConstT(), set, state)

      case FinSetT(IntT()) =>
        pickBasic(IntT(), set, state)

      case FinSetT(BoolT()) =>
        pickBasic(BoolT(), set, state)

      case FinSetT(t@FinSetT(_)) =>
        pickSet(t, set, state)

      case FinSetT(t@FunT(FinSetT(argt), rest)) =>
        pickFun(t, set, state)

      case FinFunSetT(domt @ FinSetT(_), cdm @ FinSetT(rest)) =>
        pickFunFromFunSet(FunT(domt, rest), set, state)

      case _ =>
        throw new RewriterException("Cannot pick an element from a set of type: " + set.cellType)
    }

  }

  /**
    * Implements SE-PICK-BASIC, that is, assume that the picked element has one of the basic types:
    * integer, Boolean, or constant.
    *
    * @param cellType a cell type to assign to the picked cell.
    * @param set a set of cells
    * @param state a symbolic state
    * @return a new symbolic state with the expression holding a fresh cell that stores the picked element.
    */
  def pickBasic(cellType: CellT, set: ArenaCell, state: SymbState): SymbState = {
    var arena = state.arena.appendCell(cellType)
    val resultCell = arena.topCell
    // introduce a new failure predicate
    arena = arena.appendCell(FailPredT())
    val failPred = arena.topCell
    // compare the set contents with the result
    val setCells = arena.getHas(set)
    val eqState = rewriter.lazyEq.cacheEqConstraints(state, setCells.map(e => (e, resultCell)))

    // the new element equals to an existing element in the set
    def mkIn(domElem: ArenaCell): TlaEx = {
      val inSet = tla.in(domElem, set)
      tla.and(inSet, rewriter.lazyEq.safeEq(domElem, resultCell)) // pre-cached constraints by lazy equality
    }

    val found = tla.or(setCells.map(mkIn): _*)
    val existsBasicOrFailure = decorateWithFailure(found, set, setCells, resultCell, failPred)
    rewriter.solverContext.assertGroundExpr(existsBasicOrFailure)
    eqState.setArena(arena).setRex(resultCell)
  }

  /**
    * Implements SE-PICK-SET, that is, assume that the picked element is a set itself.
    *
    * @param cellType a cell type to assign to the picked cell.
    * @param set a set of cells
    * @param state a symbolic state
    * @return a new symbolic state with the expression holding a fresh cell that stores the picked element.
    */
  def pickSet(cellType: CellT, set: ArenaCell, state: SymbState): SymbState = {
    var arena = state.arena.appendCell(cellType)
    val resultCell = arena.topCell
    arena = arena.appendCell(cellType)
    val auxCell = arena.topCell
    // introduce a new failure predicate
    arena = arena.appendCell(FailPredT())
    val failPred = arena.topCell

    val elems = arena.getHas(set)
    // get all the cells pointed by the elements of the set
    val union = elems.map(e => Set(arena.getHas(e): _*))
      .fold(Set[ArenaCell]())(_ union _)

    // the resulting cell points to all the cells in the union
    arena = union.foldLeft(arena)((a, e) => a.appendHas(resultCell, e))

    // the auxillary cell equals to an element in the original set
    def mkIn(setElem: ArenaCell): TlaEx = {
      val inSet = tla.in(setElem, set)
      // here we don't use the deep equality, just the SMT equality
      val eq = tla.eql(setElem, auxCell)
      tla.and(inSet, eq)
    }

    def mkNotIn(setElem: ArenaCell): TlaEx = {
      tla.not(tla.in(setElem, set))
    }

    def inResultIffInAux(elem: ArenaCell): Unit = {
      val inResult = tla.in(elem, resultCell)
      val inAux = tla.in(elem, auxCell)
      rewriter.solverContext.assertGroundExpr(tla.equiv(inResult, inAux))
    }

    union.foreach(inResultIffInAux)
    val found = tla.or(elems.map(mkIn): _*)
    val existsSetOrFailure = decorateWithFailure(found, set, elems, resultCell, failPred)
    rewriter.solverContext.assertGroundExpr(existsSetOrFailure)
    state.setArena(arena).setRex(resultCell)
  }

  /**
    * Implements SE-PICK-FUN, that is, assume that the picked element is a function.
    * This is, by far, the most complex case, and it easily blows up the set of constraints.
    *
    * @param cellType a cell type to assign to the picked cell.
    * @param funSet a set of cells that store functions
    * @param state a symbolic state
    * @return a new symbolic state with the expression holding a fresh cell that stores the picked element.
    */
  def pickFun(cellType: CellT, funSet: ArenaCell, state: SymbState): SymbState = {
    var arena = funMerge(state.arena, funSet) // introduce DOM and CDM, see SE-PICK-FUN
    val dom = arena.getDom(funSet)
    val cdm = arena.getCdm(funSet)
    val funType = cellType.asInstanceOf[FunT] // for now, it should be FunT, no tuple or record
    arena = arena.appendCell(cellType)
    val funCell = arena.topCell
    arena = arena.setDom(funCell, dom).setCdm(funCell, cdm)
    // introduce a new failure predicate
    arena = arena.appendCell(FailPredT())
    val failPred = arena.topCell
    // associate a function constant with the function cell
    rewriter.solverContext.declareCellFun(funCell.name, funType.argType, funType.resultType)

    // push the constraints to SMT
    val domElems = arena.getHas(dom)

    def resultEqFun(fun_i: ArenaCell): TlaEx = {
      val dom_i = arena.getDom(fun_i) // dom_i, i.e., the domain of f_{c_i}
      def inDom(c_j: ArenaCell): TlaEx = {
        // c_j \in dom <=> c_j \in DOMAIN(fun_i)
        tla.equiv(tla.in(c_j, dom), tla.in(c_j, dom_i))
      }

      def funAppEq(c_j: ArenaCell): TlaEx = {
        // c_j \in dom => f_new[c_j] = f_{fun_i}[c_j]
        tla.impl(tla.in(c_j, dom),
          tla.eql(tla.appFun(funCell, c_j),
            tla.appFun(fun_i, c_j)))
      }

      // in(c_i, c_set) /\ f_new[c'_j] = f_{c_1}[c'_j] /\ ... /\ f_new[c'_j] = f_{c_i}[c'_j]
      val inDomAndFunAppEq = domElems.map(funAppEq) ++ domElems.map(inDom)
      tla.and(tla.in(fun_i, funSet) +: inDomAndFunAppEq: _*)
    }

    val funSetElems = arena.getHas(funSet)
    val existsFun = tla.or(funSetElems.map(resultEqFun): _*)
    val existsFunOrFailure = decorateWithFailure(existsFun, funSet, funSetElems, funCell, failPred)
    rewriter.solverContext.assertGroundExpr(existsFunOrFailure)
    state.setArena(arena).setRex(funCell)
  }

  /**
    * Implements SE-PICK-SET-FUN, that is, pick a function from a set [S -> T].
    * Since we construct [S -> T] symbolically, it is easy to pick a function by imposing the constraints
    * from S and T.
    *
    * @param cellType a cell type to assign to the picked cell.
    * @param funSet a function set [S -> T]
    * @param state a symbolic state
    * @return a new symbolic state with the expression holding a fresh cell that stores the picked element.
    */
  def pickFunFromFunSet(cellType: CellT, funSet: ArenaCell, state: SymbState): SymbState = {
    var arena = state.arena
    val dom = arena.getDom(funSet)
    val cdm = arena.getCdm(funSet)
    val funType = cellType.asInstanceOf[FunT] // for now, only FinT is supported
    arena = arena.appendCell(cellType)
    val funCell = arena.topCell
    arena = arena.setDom(funCell, dom).setCdm(funCell, cdm)
    // introduce a new failure predicate
    arena = arena.appendCell(FailPredT())
    val failPred = arena.topCell
    // associate a function constant with the function cell
    val resultType = cellType match {
      case FunT(_, rt) => rt
      case _ => throw new RewriterException(s"Unexpected cell type $cellType in pickFun")
    }
    rewriter.solverContext.declareCellFun(funCell.name, funType.argType, funType.resultType)
    // push the constraints to SMT
    val cdmElems = arena.getHas(cdm)

    def resultInCdm(domElem: ArenaCell): TlaEx = {
      def funAppInCdm(cdmElem: ArenaCell): TlaEx = {
        // cdmElem \in cdm /\ f_new[domElem] = cdmElem
        tla.and(tla.in(cdmElem, cdm),
          tla.eql(tla.appFun(funCell, domElem), cdmElem))
      }

      tla.or(tla.not(tla.in(domElem, dom)) +: cdmElems.map(funAppInCdm): _*)
    }

    val domElems = arena.getHas(dom)
    val existsFun = tla.and(domElems.map(resultInCdm): _*)
    val existsFunOrFailure = decorateWithFailure(existsFun, funSet, domElems, funCell, failPred)
    rewriter.solverContext.assertGroundExpr(existsFunOrFailure)
    state.setArena(arena).setRex(funCell)
  }

  /**
    * Implements the rule SE-FUN-MERGE: it extracts the domains and co-domains from all the cells stored in the set
    * and decorates the set cell with the edges 'dom' and 'cdm' that point to the unions of all domains and co-domains,
    * respectively.
    *
    * @param arena an arena
    * @param funSetCell a set of cells that store functions
    * @return the new arena, in which funSetCell has two links:
    *         dom for the union of element domains and cdm for the union of element domains
    */
  def funMerge(arena: Arena, funSetCell: ArenaCell): Arena = {
    if (arena.hasDom(funSetCell) && arena.hasCdm(funSetCell)) {
      arena
    } else {
      val (argType: CellT, resultType: CellT) = funSetCell.cellType match {
        case FinSetT(FunT(FinSetT(at), rt)) =>
          (at, rt)

        case _ =>
          throw new RewriterException("Unexpected type for a set of functions: " + funSetCell.cellType)
      }

      val (newArena: Arena, cells: Seq[ArenaCell]) = arena.appendCellSeq(FinSetT(argType), FinSetT(resultType))
      val dom = cells.head
      val cdm = cells.tail.head
      val domUnion = arena.getHas(funSetCell).map(e => Set(arena.getHas(arena.getDom(e)): _*))
        .fold(Set[ArenaCell]())(_ union _)
      val cdmUnion = arena.getHas(funSetCell).map(e => Set(arena.getHas(arena.getCdm(e)): _*))
        .fold(Set[ArenaCell]())(_ union _)
      val newArena2 = domUnion.foldLeft(newArena)((a, e) => a.appendHas(dom, e))
      val newArena3 = cdmUnion.foldLeft(newArena2)((a, e) => a.appendHas(cdm, e))
      newArena3.setDom(funSetCell, dom).setCdm(funSetCell, cdm)
    }
  }

  // wrap an SMT constraint with a failure case
  private def decorateWithFailure(found: TlaEx, set: ArenaCell, setElems: Seq[ArenaCell],
                                  result: ArenaCell, failure: ArenaCell): TlaEx = {
    def mkNotIn(domElem: ArenaCell): TlaEx = {
      tla.not(tla.in(domElem, set))
    }

    val setEmptyInRuntime = tla.and(setElems.map(mkNotIn): _*)
    if (setElems.isEmpty) {
      failure // statically flag a failure
    } else {
      tla.and(tla.and(tla.not(failure), found), tla.eql(failure, setEmptyInRuntime))
    }
  }
}
