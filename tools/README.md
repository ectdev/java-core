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
