// "Create local variable 'foo'" "false"
// ACTION: Rename reference
// ACTION: Add 'f =' to argument
// ERROR: Unresolved reference: foo
fun test(f: (Int) -> Int) {}

fun refer() {
    val v = test(::<caret>foo)
}