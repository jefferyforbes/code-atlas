package atlas.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComplexityExtractorTest {

    @Test
    fun testSimpleFunctionHasZeroCognitiveComplexity() {
        val code = listOf(
            "fun add(a: Int, b: Int): Int {",
            "    return a + b",
            "}"
        )
        val vector = ComplexityExtractor.calculate(code, code, "kotlin", isComposable = false)
        assertEquals(1, vector.cyclomatic)
        assertEquals(0, vector.cognitive)
        assertEquals(0, vector.maxNesting)
        assertEquals(3, vector.sloc)
        assertEquals(2, vector.paramCount)
        assertEquals(1, vector.returnCount)

        val score = ComplexityExtractor.computeScore(vector)
        assertEquals(100.0, score)
        assertEquals("A", ComplexityExtractor.computeGrade(score))
    }

    @Test
    fun testSingleIfAndElvisIncrementsComplexity() {
        val code = listOf(
            "fun process(name: String?): String {",
            "    val valid = name ?: return \"unknown\"",
            "    if (valid.isNotEmpty()) {",
            "        return valid.uppercase()",
            "    }",
            "    return \"empty\"",
            "}"
        )
        val vector = ComplexityExtractor.calculate(code, code, "kotlin", isComposable = false)
        // ?: (+1 cyclo, +1 cog), if (+1 cyclo, +1 cog)
        assertEquals(3, vector.cyclomatic)
        assertEquals(2, vector.cognitive)
        assertEquals(1, vector.maxNesting)
        assertEquals(3, vector.returnCount)
    }

    @Test
    fun testNestedControlStructuresAccumulateNestingPenalties() {
        val code = listOf(
            "fun nestedCalc(matrix: List<List<Int>>): Int {",
            "    var sum = 0",
            "    for (row in matrix) {",
            "        for (item in row) {",
            "            if (item > 0) {",
            "                sum += item",
            "            }",
            "        }",
            "    }",
            "    return sum",
            "}"
        )
        val vector = ComplexityExtractor.calculate(code, code, "kotlin", isComposable = false)
        // for 1: cog 1 (nesting 0)
        // for 2: cog 2 (1 + nesting 1)
        // if:    cog 3 (1 + nesting 2)
        // total cognitive = 1 + 2 + 3 = 6
        assertEquals(6, vector.cognitive)
        assertEquals(4, vector.cyclomatic) // 1 base + for + for + if
        assertEquals(3, vector.maxNesting)
    }

    @Test
    fun testComposeFilterInvariantDiscountsDeclarativeContainers() {
        val composableCode = listOf(
            "@Composable",
            "fun UserCard(visible: Boolean) {",
            "    Column {",
            "        Box {",
            "            Surface {",
            "                if (visible) {",
            "                    Text(\"Hello\")",
            "                }",
            "            }",
            "        }",
            "    }",
            "}"
        )
        // With isComposable = true, Column, Box, Surface must NOT add cognitive nesting penalties!
        val composableVec = ComplexityExtractor.calculate(composableCode, composableCode, "kotlin", isComposable = true)
        assertEquals(1, composableVec.cognitive, "Declarative Compose containers must not incur cognitive nesting penalties")
        assertEquals(2, composableVec.cyclomatic)

        // Without Compose filter (regular function), Column/Box/Surface would add nesting penalties
        val regularVec = ComplexityExtractor.calculate(composableCode, composableCode, "kotlin", isComposable = false)
        assertTrue(regularVec.cognitive > 1, "Regular function should treat lambdas normally")
    }

    @Test
    fun testPythonIndentationComplexity() {
        val pyCode = listOf(
            "def calculate(items, threshold):",
            "    total = 0",
            "    for x in items:",
            "        if x > threshold:",
            "            total += x",
            "        elif x == 0:",
            "            continue",
            "        else:",
            "            total -= 1",
            "    return total"
        )
        val vector = ComplexityExtractor.calculate(pyCode, pyCode, "python")
        // for: cog 1
        // if:  cog 2 (1 + nesting 1)
        // elif: cog 1 (structural)
        // else: cog 1 (structural)
        // total cognitive = 5
        assertEquals(5, vector.cognitive)
        assertEquals(4, vector.cyclomatic)
        assertEquals(2, vector.maxNesting)
        assertEquals(2, vector.paramCount)
    }

    @Test
    fun testTypeScriptAndRiskIndex() {
        val tsCode = listOf(
            "function evaluate(a: number, b: number): number {",
            "    if (a > 0 && b > 0) {",
            "        return a > b ? a : b;",
            "    }",
            "    return 0;",
            "}"
        )
        val vector = ComplexityExtractor.calculate(tsCode, tsCode, "typescript")
        assertTrue(vector.cognitive >= 2)
        assertTrue(vector.cyclomatic >= 3)

        // Test Architectural Risk Index calculation
        // Risk = Cognitive * (1 + ln(1 + InDegree))
        val risk = ComplexityExtractor.computeRiskIndex(cognitive = 10, inDegree = 10)
        // 10 * (1 + ln(11)) = 10 * (1 + 2.397895) = 33.97895
        assertTrue(risk in 33.9..34.1, "Risk index should match Cognitive * (1 + ln(1 + inDegree))")
    }

    @Test
    fun testPolyglotAstExtractorPopulatesComplexityOnNodes() {
        val ktSource = """
            package com.example

            fun complexMethod(x: Int, y: Int): Int {
                if (x > 0) {
                    if (y > 0) {
                        return x + y
                    }
                }
                return 0
            }
        """.trimIndent()

        val (nodes, _, _) = PolyglotAstExtractor.extractFile("proj", "src/Main.kt", ktSource)
        val funcNode = nodes.find { it.name == "complexMethod" }
        kotlin.test.assertNotNull(funcNode)
        assertEquals(3, funcNode.cognitiveComplexity)
        assertEquals(3, funcNode.cyclomaticComplexity)
        assertEquals(2, funcNode.maxNestingDepth)
        assertTrue(funcNode.complexityScore < 100.0)
    }

    @Test
    fun testRustMatchFatArrowBranchesAndDefaultArm() {
        val rustCode = listOf(
            "fn handle_event(event: Event) -> i32 {",
            "    match event {",
            "        Event::Start => 1,",
            "        Event::Stop => 2,",
            "        Event::Pause => 3,",
            "        _ => 0,",
            "    }",
            "}"
        )
        val vector = ComplexityExtractor.calculate(rustCode, rustCode, "rust")
        // match: 1 cyclo, 1 cog. Branches: Start (+1), Stop (+1), Pause (+1). Default '_' does not increment.
        // Base cyclomatic = 1. Total cyclomatic = 1 base + 1 match + 3 branches = 5.
        assertEquals(5, vector.cyclomatic, "Rust match should count fat-arrow branches but omit default arm")
        assertEquals(1, vector.cognitive, "Rust match cognitive score")
        assertEquals(1, vector.paramCount, "Rust parameter count")
    }

    @Test
    fun testKotlinWhenBranchesDoNotCountNestedLambdasOrFunctionTypes() {
        val ktCode = listOf(
            "fun handleState(state: State) {",
            "    when (state) {",
            "        is State.Success -> {",
            "            val names = state.items.map { item -> item.name }",
            "            val filter = { x: Int -> x > 0 }",
            "        }",
            "        is State.Loading -> showSpinner()",
            "        else -> showError()",
            "    }",
            "}"
        )
        val vector = ComplexityExtractor.calculate(ktCode, ktCode, "kotlin")
        // when (+1 cyclo), is Success -> (+1 cyclo), is Loading -> (+1 cyclo), else -> (+0 cyclo)
        // Nested lambdas '{ item ->' and '{ x ->' must NOT increment cyclomatic branches!
        // Total cyclomatic: 1 base + 1 when + 2 branches = 4.
        assertEquals(4, vector.cyclomatic, "When branches must increment cyclomatic, but nested lambdas must not")
    }

    @Test
    fun testCppSwitchWithPointerDereferenceDoesNotCountArrowAsBranch() {
        val cppCode = listOf(
            "void process(State* ptr, int mode) {",
            "    switch (mode) {",
            "        case 1:",
            "            ptr->execute();",
            "            break;",
            "        case 2:",
            "            ptr->reset();",
            "            break;",
            "        default:",
            "            break;",
            "    }",
            "}"
        )
        val vector = ComplexityExtractor.calculate(cppCode, cppCode, "cpp")
        // switch (+1 cyclo), case 1 (+1 cyclo), case 2 (+1 cyclo)
        // ptr->execute() and ptr->reset() must NOT increment cyclomatic!
        // Total cyclomatic: 1 base + 1 switch + 2 cases = 4.
        assertEquals(4, vector.cyclomatic, "C++ switch must count cases, and ptr-> must NOT be counted as branch")
        assertEquals(2, vector.paramCount, "C++ parameter count should be 2")
    }

    @Test
    fun testMultilineParameterCounting() {
        val multilineCode = listOf(
            "fun renderCard(",
            "    title: String,",
            "    subtitle: String?,",
            "    count: Int = 0,",
            "    onClick: () -> Unit",
            "): View {",
            "    return View()",
            "}"
        )
        val vector = ComplexityExtractor.calculate(multilineCode, multilineCode, "kotlin")
        assertEquals(4, vector.paramCount, "extractParamCount must count all 4 multiline parameters")
    }
}
