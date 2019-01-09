package at.forsyte.apalache.tla.bmcmt.types.eager

import at.forsyte.apalache.tla.bmcmt.types._
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.actions.TlaActionOper
import at.forsyte.apalache.tla.lir.control.TlaControlOper
import at.forsyte.apalache.tla.lir.oper._
import at.forsyte.apalache.tla.lir.values.{TlaBool, TlaInt, TlaStr}

import scala.collection.immutable.SortedMap

/**
  * An eager type finder that propagates types from the leaves to the root.
  * As it can easily fail to find a type, the user has to write type annotations.
  * In contrast, to our first type inference approach, this engine is not trying to be
  * smart at all, and it is not doing any form of unification.
  *
  * This class assumes that some pre-processing has been done:
  *
  * 1. The definitions of all user-defined operators have been expanded (no recursive operators),
  * 2. All variable names are unique, including the bound variables.
  *
  * @author Igor Konnov
  */
class TrivialTypeFinder extends TypeFinder[CellT] {
  private var varTypes: SortedMap[String, CellT] = SortedMap()
  private var typeAnnotations: Map[UID, CellT] = Map()
  private var errors: Seq[TypeInferenceError] = Seq()

  /**
    * Get the types that were assigned to variables by inferAndSave
    *
    * @return a map from variable names to types
    */
  def getVarTypes: Map[String, CellT] = varTypes


  /**
    * Given a TLA+ expression, reconstruct the types and store them in an internal storage.
    * If the expression is not well-typed, diagnostic messages can be accessed with getTypeErrors.
    * The main goal of this method is to assign types to the free and bound variables
    * (as we do not consider operators).
    *
    * @param expr a TLA+ expression.
    * @return Some(type), if successful, and None otherwise
    */
  override def inferAndSave(expr: TlaEx): Option[CellT] = {
    // This class implements a very simple form of type inference from bottom to top.
    // As soon as we cannot infer types, we complain that the type annotations are not good enough.
    expr match {
      case OperEx(TlaOper.eq, OperEx(TlaActionOper.prime, NameEx(varName)), rhs) =>
        inferAndSave(rhs) match {
          case Some(tp) =>
            val primedVar = varName + "'"
            assert(!varTypes.contains(primedVar))
            varTypes = varTypes + (primedVar -> tp)
            Some(BoolT())

          case _ => None
        }

      case OperEx(TlaSetOper.in, OperEx(TlaActionOper.prime, NameEx(varName)), rhs) =>
        inferAndSave(rhs) match {
          case Some(FinSetT(elemT)) =>
            val primedVar = varName + "'"
            assert(!varTypes.contains(primedVar))
            varTypes = varTypes + (primedVar -> elemT)
            Some(BoolT())

          case _ => None
        }

      case OperEx(TlaSetOper.filter, NameEx(x), set, pred) =>
        inferAndSave(set) match {
          case Some(setT@FinSetT(elemT)) =>
            assert(!varTypes.contains(x))
            varTypes = varTypes + (x -> elemT)
            val predT = inferAndSave(pred)
            if (predT.contains(BoolT())) {
              Some(setT)
            } else {
              errors +:= new TypeInferenceError(pred, "Expected a Boolean, found: " + predT)
              None
            }

          case tp@_ =>
            errors +:= new TypeInferenceError(set, "Expected a set, found: " + tp)
            None
        }

      case OperEx(TlaSetOper.map, mapEx, varsAndSets@_*) =>
        val names = varsAndSets.zipWithIndex.collect { case (NameEx(n), i) if i % 2 == 0 => n }
        val sets = varsAndSets.zipWithIndex.collect { case (e, i) if i % 2 == 1 => e }

        def bind(pair: Tuple2[String, TlaEx]): Unit = {
          inferAndSave(pair._2) match {
            case Some(setT@FinSetT(elemT)) =>
              assert(!varTypes.contains(pair._1))
              varTypes = varTypes + (pair._1 -> elemT)

            case tp@_ =>
              errors +:= new TypeInferenceError(pair._2, "Expected a set, found: " + tp)
          }
        }

        names.zip(sets).foreach(bind)
        Some(FinSetT(inferAndSave(mapEx).getOrElse(UnknownT())))

      case OperEx(TlaFunOper.funDef, funEx, varsAndSets@_*) =>
        val names = varsAndSets.zipWithIndex.collect { case (NameEx(n), i) if i % 2 == 0 => n }
        val sets = varsAndSets.zipWithIndex.collect { case (e, i) if i % 2 == 1 => e }

        def bind(pair: Tuple2[String, TlaEx]): Unit = {
          inferAndSave(pair._2) match {
            case Some(setT@FinSetT(elemT)) =>
              assert(!varTypes.contains(pair._1))
              varTypes = varTypes + (pair._1 -> elemT)

            case tp@_ =>
              errors +:= new TypeInferenceError(pair._2, "Expected a set, found: " + tp)
          }
        }

        names.zip(sets).foreach(bind)
        val resT = inferAndSave(funEx).getOrElse(UnknownT())
        val domT =
          if (names.length == 1) {
            // a function of one argument
            FinSetT(varTypes(names.head))
          } else {
            // a function of multiple arguments is a function from a Cartesian product to the result type
            FinSetT(TupleT(names.map(varTypes(_))))
          }
        Some(FunT(domT, resT))

      case OperEx(op, NameEx(x), set, pred)
        if op == TlaBoolOper.exists || op == TlaBoolOper.forall || op == TlaOper.chooseBounded =>
        inferAndSave(set) match {
          case Some(setT@FinSetT(elemT)) =>
            assert(!varTypes.contains(x))
            varTypes = varTypes + (x -> elemT)
            val predT = inferAndSave(pred)
            if (predT.contains(BoolT())) {
              if (op == TlaOper.chooseBounded) {
                Some(elemT) // CHOOSE
              } else {
                Some(BoolT()) // exists/forall
              }
            } else {
              errors +:= new TypeInferenceError(pred, "Expected a Boolean, found: " + predT)
              None
            }

          case tp@_ =>
            errors +:= new TypeInferenceError(set, "Expected a set, found: " + tp)
            None
        }

      case OperEx(BmcOper.withType, ex, annot) =>
        val exT = inferAndSave(ex)
        val annotT = AnnotationParser.parse(annot)
        val unifier = unifyOption(Some(annotT), exT)
        if (unifier.isDefined) {
          // save the type annotation and return the type
          typeAnnotations += (ex.safeId -> unifier.get)
          unifier
        } else {
          errors +:= new TypeInferenceError(annot,
            "No unifier for type annotation %s and expression %s".format(annot, ex))
          None
        }

      case OperEx(_, args@_*) =>
        val argTypes = args.map(inferAndSave)
        if (argTypes.forall(_.isDefined))
          Some(compute(expr, argTypes.map(_.get): _*))
        else
          None


      case NameEx(name) =>
        if (varTypes.contains(name)) {
          Some(varTypes(name))
        } else {
          errors +:= new TypeInferenceError(expr, "Failed to find type of variable " + name)
          None
        }

      case ValEx(_) =>
        Some(compute(expr))

      case _ =>
        None
    }
  }

  /**
    * Retrieve the type errors from the latest call to inferAndSave.
    *
    * @return a list of type errors
    */
  override def getTypeErrors: Seq[TypeInferenceError] = errors

  /**
    * Given a TLA+ expression and the types of its arguments, compute the resulting type, if possible.
    *
    * @param ex       a TLA+ expression
    * @param argTypes the types of the arguments.
    * @return the resulting type, if it can be computed
    * @throws TypeInferenceError if the type cannot be computed.
    */
  override def compute(ex: TlaEx, argTypes: CellT*): CellT = {
    if (ex.ID.valid && typeAnnotations.contains(ex.ID)) {
      // this expression has been annotated with a type
      typeAnnotations(ex.ID)
    } else {
      // chain partial functions to handle different types of operators with different functions
      val handlers =
        (computeValues :: computeBasicOps(argTypes)
          :: computeBoolOps(argTypes) :: computeIntOps(argTypes)
          :: computeControlOps(argTypes)
          :: computeSetCtors(argTypes) :: computeFunCtors(argTypes)
          :: computeSetOps(argTypes) :: computeFunOps(argTypes)
          :: computeFunApp(argTypes)
          :: computeFiniteSetOps(argTypes)
          :: computeSeqOps(argTypes)
          :: computeMiscOps(argTypes)
          :: notImplemented :: Nil) reduceLeft (_ orElse _)
      handlers(ex)
    }
  }

  private def computeValues: PartialFunction[TlaEx, CellT] = {
    case ValEx(TlaInt(_)) =>
      IntT()

    case ValEx(TlaBool(_)) =>
      BoolT()

    case ValEx(TlaStr(_)) =>
      ConstT()
  }

  private def computeBasicOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case NameEx(name) if varTypes.contains(name) =>
      varTypes(name)

    case OperEx(TlaActionOper.prime, NameEx(name)) if varTypes.contains(name) =>
      varTypes(name + "'")

    case ex@OperEx(op, _, _)
      if op == TlaOper.eq || op == TlaOper.ne =>
      expectEqualTypes(ex, argTypes: _*)
      BoolT()

    case ex@OperEx(op@TlaOper.chooseBounded, x, set, pred) =>
      val xType = argTypes.head
      val setType = argTypes.tail.head
      val predType = argTypes.tail.tail.head
      setType match {
        case FinSetT(elemT) =>
          expectType(elemT, x, xType)
          expectType(BoolT(), pred, predType)
          elemT

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaOper.chooseUnbounded, x, pred) =>
      val xType = argTypes.head
      val predType = argTypes.tail.head
      expectType(BoolT(), pred, predType)
      xType

    case ex@OperEx(op@TlaOper.chooseIdiom, _) =>
      argTypes match {
        case Seq(FinSetT(elemT)) =>
          elemT

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaOper.label, _, _, _*) =>
      val decoratedExprType = argTypes.head
      val nameAndArgTypes = argTypes.tail
      nameAndArgTypes.foreach(expectType(ConstT(), ex, _))
      decoratedExprType
  }

  private def computeSetCtors(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(TlaSetOper.enumSet, _*) =>
      if (argTypes.isEmpty) {
        // This case typically causes problems, as any operation with
        // a concrete type would fail. One has to use a type annotation.
        FinSetT(UnknownT())
      } else {
        expectEqualTypes(ex, argTypes: _*)
        FinSetT(argTypes.head)
      }

    case ex@OperEx(op@TlaSetOper.funSet, _, _) =>
      argTypes match {
        case Seq(FinSetT(argT), FinSetT(resT)) =>
          // FinT expects the types of the domain and the result (not of the co-domain!)
          FinSetT(FunT(FinSetT(argT), resT))

        case _ => errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(TlaSetOper.recSet, args@_*) =>
      assert(argTypes.nonEmpty)
      val fieldNames = deinterleave(args, 0, 2)
        .collect { case ValEx(TlaStr(a)) => a }
      val _, fieldTypes = deinterleave(argTypes, 1, 2)
      val elemTypes = argTypes.collect { case FinSetT(t) => t }
      if (elemTypes.size < fieldTypes.size) {
        error(ex, "Only explicit sets are supported in sets of records")
      }
      assert(fieldNames.length == fieldTypes.length)
      FinSetT(RecordT(SortedMap(fieldNames.zip(elemTypes): _*)))

    case ex@OperEx(op@TlaSetOper.powerset, _) =>
      argTypes match {
        case Seq(FinSetT(elemT)) =>
          FinSetT(FinSetT(elemT))

        // what about SUBSET [S -> T]?

        case _ => errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(TlaSetOper.times, _*) =>
      assert(argTypes.nonEmpty)
      val elemTypes = argTypes.collect({ case FinSetT(t) => t }) // using partial functions
      if (elemTypes.size < argTypes.size) {
        error(ex, "Only explicit sets are supported in Cartesian products")
      }
      FinSetT(TupleT(elemTypes))
  }

  private def computeSetOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(op@TlaSetOper.union, _) =>
      argTypes match {
        case Seq(FinSetT(FinSetT(elemT))) =>
          FinSetT(elemT)

        case _ => errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaSetOper.filter, _, _, _) =>
      argTypes match {
        case Seq(_, FinSetT(elemT), BoolT()) =>
          FinSetT(elemT)

        // what about {f \in [S -> T] : ... }?
        // what about {f \in [a: S, B: T] |-> ... }?

        case _ => errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaSetOper.map, _*) =>
      for (pair <- argTypes.tail.zipWithIndex) {
        if (pair._2 % 2 == 1) {
          pair._1 match {
            case FinSetT(_) => ()
            // what about {f \in [S -> T] |-> ... }?
            // what about {f \in [a: S, B: T] |-> ... }?
            case _ => errorUnexpected(ex, op.name, argTypes)
          }
        }
      }
      argTypes.head

    case ex@OperEx(op, _, _) if op == TlaSetOper.in || op == TlaSetOper.notin =>
      argTypes match {
        case Seq(memT, FinSetT(elemT)) =>
          expectEqualTypes(ex, memT, elemT)
          BoolT()

        // what about f \in [S -> T]?
        // what about f \in [a: S, B: T]?

        case _ => errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op, _, _)
      if op == TlaSetOper.subsetProper || op == TlaSetOper.subseteq
        || op == TlaSetOper.supsetProper || op == TlaSetOper.supseteq =>
      argTypes match {
        case Seq(FinSetT(leftT), FinSetT(rightT)) =>
          expectEqualTypes(ex, leftT, rightT)
          BoolT()

        // what about f \in [S -> T]?
        // what about f \in [a: S, B: T]?
        case _ => errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op, _, _)
      if op == TlaSetOper.cup || op == TlaSetOper.cap || op == TlaSetOper.setminus =>
      argTypes match {
        case Seq(FinSetT(leftT), FinSetT(rightT)) =>
          expectEqualTypes(ex, leftT, rightT)
          FinSetT(leftT)

        case _ => errorUnexpected(ex, op.name, argTypes)
      }
  }

  private def computeFunCtors(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(TlaFunOper.tuple) =>
      error(ex, "The type of an empty sequence is unknown, use annotation, e.g., <<>> <: <<Int>>")

    case ex@OperEx(op@TlaFunOper.tuple, _*) =>
      TupleT(argTypes)

    case ex@OperEx(op@TlaFunOper.enum, args@_*) =>
      assert(argTypes.nonEmpty)
      val fieldNames = deinterleave(args, 0, 2) collect { case ValEx(TlaStr(a)) => a }
      val namesTypes = deinterleave(argTypes, 0, 2) collect { case ConstT() => true }

      if (namesTypes.size != fieldNames.size) {
        errorUnexpected(ex, op.name, argTypes)
      }
      val fieldTypes = deinterleave(argTypes, 1, 2)
      assert(fieldNames.length == fieldTypes.length)
      RecordT(SortedMap(fieldNames.zip(fieldTypes): _*))
  }

  private def computeFunApp(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(op@TlaFunOper.app, fun, arg) =>
      val funType = argTypes.head
      val argType = argTypes.tail.head
      funType match {
        case FunT(FinSetT(funArgT), funResT) if funArgT == argType =>
          funResT

        case TupleT(elemTypes) if argType == IntT() =>
          // try to extract an integer from the expression
          arg match {
            case ValEx(TlaInt(i)) =>
              if (i >= 1 && i <= elemTypes.length) {
                elemTypes(i.toInt - 1) // the argument is within a small range, so toInt should work
              } else {
                error(ex, "The tuple argument is out of range: " + i)
              }

            case _ => error(ex, "Expected an integer constant as the tuple argument, found: " + arg)
          }

        case RecordT(fields) if argType == ConstT() =>
          // try to extract a string from the expression
          arg match {
            case ValEx(TlaStr(s)) =>
              if (fields.contains(s)) {
                fields(s)
              } else {
                error(ex, "Unexpected record field name: " + s)
              }

            case _ => error(ex, "Expected a string constant as the tuple argument, found: " + arg)
          }

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }
  }

  private def computeFunOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(op@TlaFunOper.funDef, e, bindings@_*) =>
      val resType = argTypes.head
      val setTypes = deinterleave(argTypes.tail, 1, 2)
      val varTypes = deinterleave(argTypes.tail, 0, 2) collect { case ConstT() => true }
      if (varTypes.length != setTypes.length) {
        errorUnexpected(ex, op.name, argTypes)
      } else {
        val elemTypes = setTypes.collect { case FinSetT(et) => et }
        if (elemTypes.length != setTypes.length) {
          // wrong types were passed
          errorUnexpected(ex, op.name, argTypes)
        }
        if (setTypes.length == 1) {
          // a single-argument function
          FunT(setTypes.head, resType)
        } else {
          // a multi-argument function, which means it receives a Cartesian product
          FunT(FinSetT(TupleT(elemTypes)), resType)
        }
      }

    case ex@OperEx(op@TlaFunOper.except, e, bindings@_*) =>
      val funType = argTypes.head
      // In principle, we could just return the function itself.
      // But we also check the argument types to be on a well-typed side.
      val indexTypes = deinterleave(argTypes.tail, 0, 2)
      val valueTypes = deinterleave(argTypes.tail, 1, 2)
      val argT =
        funType match {
          case FunT(FinSetT(tup@TupleT(_)), resT) => tup
          case FunT(FinSetT(elemT), resT) => TupleT(Seq(elemT))
          case _ => error(ex, "Expected a function type, found: " + funType)
        }
      for (idx <- indexTypes) {
        if (idx != argT) {
          error(ex, "Expected an index of type %s, found: %s".format(argT, idx))
        }
      }

      funType

    case ex@OperEx(TlaFunOper.domain, fun) =>
      argTypes.head match {
        case FunT(domT, _) => domT
        case TupleT(_) => FinSetT(IntT())
        case RecordT(_) => FinSetT(ConstT())
        case _ => error(ex, "Unexpected type of DOMAIN argument: " + ex)
      }
  }

  private def computeIntOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(op, _) if op == TlaArithOper.uminus =>
      assert(argTypes.length == 1)
      expectType(IntT(), ex, argTypes.head)
      IntT()

    case ex@OperEx(TlaArithOper.dotdot, _, _) =>
      assert(argTypes.length == 2)
      argTypes.foreach(expectType(IntT(), ex, _))
      FinSetT(IntT())

    case ex@OperEx(op, _, _)
      if op == TlaArithOper.plus || op == TlaArithOper.minus
        || op == TlaArithOper.mult || op == TlaArithOper.div || op == TlaArithOper.mod || op == TlaArithOper.exp =>
      assert(argTypes.length == 2)
      argTypes.foreach(expectType(IntT(), ex, _))
      IntT()

    case ex@OperEx(op, _, _)
      if op == TlaArithOper.lt || op == TlaArithOper.gt || op == TlaArithOper.le || op == TlaArithOper.ge =>
      assert(argTypes.length == 2)
      argTypes.foreach(expectType(IntT(), ex, _))
      BoolT()

    case ex@OperEx(op, _*)
      if op == TlaArithOper.sum || op == TlaArithOper.prod =>
      argTypes.foreach(expectType(IntT(), ex, _))
      IntT()
  }

  private def computeBoolOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(TlaBoolOper.not, _) =>
      assert(argTypes.length == 1)
      expectType(BoolT(), ex, argTypes.head)
      BoolT()

    case ex@OperEx(op, _, _)
      if op == TlaBoolOper.implies || op == TlaBoolOper.equiv =>
      assert(argTypes.length == 2)
      argTypes.foreach(expectType(BoolT(), ex, _))
      BoolT()

    case ex@OperEx(op, _*)
      if op == TlaBoolOper.and || op == TlaBoolOper.or || op == TlaBoolOper.orParallel =>
      argTypes.foreach(expectType(BoolT(), ex, _))
      BoolT()

    case ex@OperEx(op, x, set, pred) if op == TlaBoolOper.forall || op == TlaBoolOper.exists =>
      val xType = argTypes.head
      val setType = argTypes.tail.head
      val predType = argTypes.tail.tail.head
      expectType(BoolT(), pred, predType)
      setType match {
        case FinSetT(elemT) =>
          expectType(elemT, x, xType)

        case _ =>
          errorUnexpected(set, op.name, argTypes)
      }
      BoolT()
  }

  private def computeControlOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(TlaControlOper.ifThenElse, predEx, thenEx, elseEx) =>
      assert(argTypes.length == 3)
      expectType(BoolT(), predEx, argTypes.head)
      val leftType = argTypes.tail.head
      expectEqualTypes(ex, argTypes.tail: _*)
      leftType

    case ex@OperEx(TlaControlOper.caseNoOther, _*) =>
      val guards = argTypes.zipWithIndex.collect { case (a, i) if i % 2 == 0 => a }
      val actions = argTypes.zipWithIndex.collect { case (a, i) if i % 2 == 1 => a }
      guards.foreach(expectType(BoolT(), ex, _))
      expectEqualTypes(ex, actions: _*)
      actions.head

    case ex@OperEx(TlaControlOper.caseWithOther, _*) =>
      val guards = argTypes.zipWithIndex.collect { case (a, i) if i % 2 == 1 => a }
      val actions = argTypes.zipWithIndex.collect { case (a, i) if i % 2 == 0 => a }
      guards.foreach(expectType(BoolT(), ex, _))
      expectEqualTypes(ex, actions: _*)
      actions.head
  }

  private def computeFiniteSetOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(op, _)
      if op == TlaFiniteSetOper.isFiniteSet || op == TlaFiniteSetOper.cardinality =>
      assert(argTypes.length == 1)
      argTypes.head match {
        case FinSetT(_) =>
          if (op == TlaFiniteSetOper.isFiniteSet)
            BoolT()
          else
            IntT()

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }
  }

  private def computeSeqOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(op, _)
      if op == TlaSeqOper.head || op == TlaSeqOper.tail || op == TlaSeqOper.len =>
      assert(argTypes.length == 1)
      argTypes.head match {
        case SeqT(elemT) =>
          if (op == TlaSeqOper.head)
            elemT
          else if (op == TlaSeqOper.tail)
            SeqT(elemT)
          else IntT() // len

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaSeqOper.append, _, argEx) =>
      assert(argTypes.length == 2)
      argTypes.head match {
        case SeqT(elemT) =>
          expectType(elemT, argEx, argTypes.tail.head)
          SeqT(elemT)

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaSeqOper.concat, lex, rex) =>
      assert(argTypes.length == 2)
      argTypes.head match {
        case SeqT(elemT) =>
          expectType(SeqT(elemT), rex, argTypes.tail.head)
          SeqT(elemT)

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaSeqOper.subseq, seq, start, end) =>
      assert(argTypes.length == 3)
      argTypes.head match {
        case SeqT(elemT) =>
          expectType(IntT(), start, argTypes.tail.head)
          expectType(IntT(), end, argTypes.tail.tail.head)
          SeqT(elemT)

        case _ =>
          errorUnexpected(ex, op.name, argTypes)
      }

    case ex@OperEx(op@TlaSeqOper.selectseq, seq, pred) =>
      // pred should be a second-level operator. How would we implement it here?
      throw new NotImplementedError("Type construction for Sequence.selectseq cannot be implemented.")
  }

  private def computeMiscOps(argTypes: Seq[CellT]): PartialFunction[TlaEx, CellT] = {
    case ex@OperEx(TlcOper.assert, expr, msg) =>
      val exprType = argTypes.head
      val msgType = argTypes.tail.head
      expectType(BoolT(), expr, exprType)
      expectType(ConstT(), msg, msgType)
      BoolT()

    case OperEx(BmcOper.withType, expr, _) =>
      argTypes.head // just return the expression type, as the proper type has been assigned already
  }

  private def expectType(expectedType: CellT, ex: TlaEx, exType: CellT): Unit = {
    if (exType != expectedType) {
      error(ex, "Expected type %s, found %s".format(expectedType, exType))
    }
  }

  private def expectEqualTypes(ex: TlaEx, types: CellT*): Unit = {
    if (types.nonEmpty) {
      val firstType = types.head

      if (types.tail.exists(_ != firstType)) {
        error(ex, "Expected equal types: %s".format(types.mkString(", ")))
      }
    }
  }

  private def errorUnexpected(ex: TlaEx, opname: String, argTypes: Seq[CellT]): CellT = {
    error(ex, "Unexpected types for %s: %s".format(opname, argTypes.mkString(", ")))
  }

  private def error(ex: TlaEx, message: String): CellT = {
    throw new TypeInferenceError(ex, message)
  }

  private def notImplemented: PartialFunction[TlaEx, CellT] = {
    case ex => throw new NotImplementedError("Type construction for %s is not implemented. Report a bug!".format(ex))
  }

  /**
    * Get a subsequence of elements whose indices satisfy the predicate: index % base == group.
    *
    * @param s     sequence
    * @param group the group number (from 0 to base - 1)
    * @param base  the divider to use in the modulo operation
    * @tparam T element type
    * @return the subsequence of s s.t. index % base == group
    */
  private def deinterleave[T](s: Seq[T], group: Int, base: Int): Seq[T] = {
    s.zipWithIndex.collect { case (e, i) if i % base == group => e }
  }
}