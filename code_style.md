
Method arrangement: if a private method is called from a single other method then it is placed in a
depth-first tree rooting from its caller method. If a private method is called from multiple other
methods then it goes after all of them, i. e. in a breadth-first manner.

Underscores in variable names: add underscores when a name has 5 words or more.

Use guard clauses for exceptions to reduce nesting, despite [Positive likely branch] policy.

Terminology: use "delete" in application to HashTable's slots and entries, "erase" in application to
Segment's allocation slots, and "remove" in application to SmoothieMap's keys, values, or entries.

Method that does the memory access is always responsible for making all necessary extraChecks to
ensure that the memory access is not corrupting. 