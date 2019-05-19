
Method arrangement: if a private method is called from a single other method then it is placed in a
depth-first tree rooting from its caller method. If a private method is called from multiple other
methods then it goes after all of them, i. e. in a breadth-first manner.

Underscores in variable names: add underscores when a name has 5 words or more.

Use guard clauses for exceptions to reduce nesting, despite [Positive likely branch] policy.