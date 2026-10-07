# tools

## `mutate.py`: do the tests notice a broken implementation?

A test suite that has never failed proves nothing. `mutate.py` breaks the source on purpose, one plausible bug
at a time, runs the module's tests, and reports whether they noticed.

```sh
# JDK 21 or newer must be the one `./mvnw` finds (JAVA_HOME or PATH). Python 3 is the only other requirement.
tools/mutate.py collections tools/mutants/collections.txt

tools/mutate.py collections tools/mutants/collections.txt "heap"   # only mutants whose name contains "heap"
```

| Result | Meaning |
| --- | --- |
| `killed` | The tests failed on the broken code: they notice this bug. |
| `SURVIVED` | The broken code passed every test. Either the tests have a gap, or the mutant does not change behaviour. |
| `INVALID` | The mutant could not be applied, or it does not compile. A compile error is not a test noticing a bug. |

The exit status is non-zero if anything survived or was invalid.

Three guards keep the numbers honest. The unmodified code is tested first, and nothing is mutated if it fails,
because every mutant would otherwise look killed. A mutant whose original text is missing or appears more than
once is reported as invalid instead of being skipped. And the original file is restored after every mutant,
including when the script is interrupted.

### The mutant files

`tools/mutants/<module>.txt` holds one block per mutant, blocks separated by a line containing only `===`.
Each block has four parts separated by a line containing only `---`: a name, the file path, the exact text to
replace (it must occur exactly once), and its replacement.

```text
LruCache: get does not refresh recency
---
collections/src/main/java/io/github/ectdev/collections/LruCache.java
---
        moveToFront(node);
        return node.value;
---
        return node.value;
```

### The mutant files that exist

| Module | Mutants | Result |
| --- | --- | --- |
| `collections` | 24 | all killed (one test gap fixed, one equivalent mutant replaced; both described below) |
| `concurrency` | 24 | all killed (a missing test for interrupting a blocked `put` was added; one near-equivalent mutant was replaced) |
| `json` | 63 | all killed (after one missing pointer test was added) |
| `ledger` | 48 | all killed |

### What a survivor taught

Writing these found one real hole in the first round. A mutant that dropped `modCount++` from
`DynamicArray.add(int, E)` survived, because the fail-fast iterator test only ever appended. The test now
tries every structural change (append, insert by index, remove, clear) behind an iterator's back.

A second survivor was an *equivalent* mutant: changing `>= 0` to `> 0` in the heap's sift-up only changes the
order in which equal elements swap, which cannot be observed with indistinguishable values and leaves a valid
heap. It was removed from the list and replaced by mutants that produce a failure someone could actually
have, such as a wrong parent index.

The mutation checks are not part of CI: they run the test suite once per mutant, which takes minutes. Run them
after changing a module.

The `json` round found a third kind of hole. Weakening the array-index scan in the JSON Pointer evaluation
(`c < '0' || c > '9'` to `c > '9'`) survived, although the test list had a dozen invalid indexes. None of them
happened to land on an existing element: `1.0` computes to 80, `-1` to a negative number. `1-` computes to
10 - 3 = 7, which is a real element, so it is the token that shows the check is missing. A list of bad inputs
proves less than it seems unless some of them are the ones a broken implementation would accept.
