![Apalache Logo](./logo-apalache.svg)

# APALACHE

A symbolic model checker for TLA+

|             master             |              unstable              |
| :----------------------------: | :--------------------------------: |
| [![master badge][]][master-ci] | [![unstable badge][]][unstable-ci] |

[master badge]: https://travis-ci.org/informalsystems/apalache.svg?branch=master
[master-ci]: https://travis-ci.org/github/informalsystems/apalache/branches
[unstable badge]: https://github.com/informalsystems/apalache/workflows/build/badge.svg
[unstable-ci]: https://github.com/informalsystems/apalache/actions?query=branch%3Aunstable+workflow%3Abuild

Apalache translates TLA+ in the logic supported by the SMT solvers, for instance, [Microsoft Z3](https://github.com/Z3Prover/z3). Apalache can check inductive invariants (for fixed or bounded parameters) and check safety of bounded executions (bounded model checking). To see the list of supported
TLA+ constructs, check the [supported features](docs/features.md). In general,
Apalache runs under the same assumptions as TLC.

## Releases

Check the [releases page](https://github.com/informalsystems/apalache/releases).

We recommend you to run the latest docker image `apalache/mc:latest` and
checkout the source code from
[master](https://github.com/informalsystems/apalache/tree/master), which accumulate
bugfixes over the latest release, see the [manual](docs/manual.md#useDocker).
To try the latest cool features, check the [unstable
branch](https://github.com/informalsystems/apalache/tree/unstable).

## Getting started

Read the [Apalache user manual](./docs/manual.md).

WIP: [Idioms for writing better TLA+](./docs/idiomatic)

## Talks

 * [Model-based testing with TLA+ and Apalache](https://youtu.be/aveoIMphzW8).
    TLA+ Community Event 2020 (October 2020).

 * [Type inference for TLA+ in Apalache](https://youtu.be/hnp25hmCMN8).
    TLA+ Community Event 2020 (October 2020).

 * [Formal Spec and Model Checking of the Tendermint Blockchain Synchronization Protocol](https://youtu.be/h2Ovc1KWlXM)
    2nd Workshop on Formal Methods for Blockchains (July 2020).

 * [Showing safety of Tendermint Consensus with TLA+ and Apalache](https://www.youtube.com/watch?v=aF20-28sMII).
    Dev session at Informal Systems (May 2020).

 * [TLA+ model checking made symbolic](https://www.youtube.com/watch?v=e66FGgRzaqw)
    OOPSLA 2019 (October 2019).

 * [Bounded model checking of TLA+ specifications with SMT](https://www.youtube.com/watch?v=Xl1--arESl8)
   TLA+ Community Event 2018 (July 2018).

## Performance

We are collecting [apalache benchmarks](https://github.com/informalsystems/apalache-tests).
See the Apalache performance when
[checking inductive invariants](https://github.com/informalsystems/apalache-tests/blob/master/results/001indinv-report.md)
and running
[bounded model checking](https://github.com/informalsystems/apalache-tests/blob/master/results/002bmc-report.md). Version 0.6.0 is a major improvement over version 0.5.2
(the version [reported at OOPSLA19](https://dl.acm.org/doi/10.1145/3360549)).

## Academic papers

To read an academic paper about the theory behind Apalache,
check our [paper at OOPSLA19](https://dl.acm.org/doi/10.1145/3360549).
Related reports and publications can be found at the
[Apalache page at TU Wien](http://forsyte.at/research/apalache/).
