package atlas.extract

import kotlin.test.Test
import kotlin.test.assertEquals

// Mock fixture parameters for hermetic offline testing without live network
class ParamComplexityExtractorTest {

    @Test
    fun testEmptyAndSingleParameter() {
        val emptyCode = listOf("fun noArgs() { }")
        assertEquals(0, ComplexityExtractor.extractParamCount(emptyCode, "noArgs", "kotlin"))

        val singleCode = listOf("fun single(name: String) { }")
        assertEquals(1, ComplexityExtractor.extractParamCount(singleCode, "single", "kotlin"))
    }

    @Test
    fun testMultilineParametersWithTrailingCommaAndComments() {
        val multiline = listOf(
            "fun processOrder(",
            "    orderId: String, // ID of order",
            "    amount: Double, /* total amount */",
            "    discount: Double = 0.0,",
            "    notifyCustomer: Boolean = true,",
            ") {"
        )
        assertEquals(4, ComplexityExtractor.extractParamCount(multiline, "processOrder", "kotlin"))
    }

    @Test
    fun testGenericFunctionsAndNestedTypeArguments() {
        val genericCode = listOf(
            "fun <T : Comparable<T>, R> transformData(",
            "    items: Map<String, List<Pair<T, R>>>,",
            "    filter: (T) -> Boolean,",
            "    defaultVal: R?",
            "): List<R> {"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(genericCode, "transformData", "kotlin"))
    }

    @Test
    fun testDefaultParameterValuesWithCommasInStringsAndLambdas() {
        val defaultValCode = listOf(
            "fun setupUi(",
            "    title: String = \"Item, Part 1, Section 2\",",
            "    callback: (Int, String) -> Unit = { id, text -> println(text) },",
            "    tags: List<String> = listOf(\"alpha\", \"beta\", \"gamma\"),",
            "    enabled: Boolean = true",
            ") {"
        )
        assertEquals(4, ComplexityExtractor.extractParamCount(defaultValCode, "setupUi", "kotlin"))
    }

    @Test
    fun testExtensionReceiverDoesNotCountAsParameter() {
        val simpleExt = listOf("fun String.padSuffix(suffix: String, count: Int): String { return this }")
        assertEquals(2, ComplexityExtractor.extractParamCount(simpleExt, "padSuffix", "kotlin"))

        val parenExt = listOf("fun (Int, String).combineWith(flag: Boolean): String { return \"\" }")
        assertEquals(1, ComplexityExtractor.extractParamCount(parenExt, "combineWith", "kotlin"))
    }

    @Test
    fun testPythonSelfAndClsExclusion() {
        val methodCode = listOf(
            "def update_profile(self, username, email, active=True):",
            "    pass"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(methodCode, "update_profile", "python"))

        val classMethodCode = listOf(
            "def create_instance(cls, payload, validate=True):",
            "    pass"
        )
        assertEquals(2, ComplexityExtractor.extractParamCount(classMethodCode, "create_instance", "python"))

        val standaloneCode = listOf(
            "def global_func(a, b, c):",
            "    pass"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(standaloneCode, "global_func", "python"))
    }

    @Test
    fun testTypeScriptConstructorAndFunction() {
        val ctorCode = listOf(
            "class Service {",
            "    constructor(private readonly client: HttpClient, public retryCount: number = 3) {",
            "    }",
            "}"
        )
        assertEquals(2, ComplexityExtractor.extractParamCount(ctorCode, "constructor", "typescript"))

        val tsFunc = listOf(
            "function computeRatio(x: number, y: number, fallback: number = 1.0): number {",
            "    return x / y;",
            "}"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(tsFunc, "computeRatio", "typescript"))
    }

    @Test
    fun testUrlAndCommentSymbolsInsideStringLiterals() {
        val multilineUrl = listOf(
            "fun fetchEndpoint(",
            "    url: String = \"https://api.example.com/v1/orders\",",
            "    timeoutMs: Long = 5000L,",
            "    apiKey: String = \"Bearer //secret//token\"",
            ") {"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(multilineUrl, "fetchEndpoint", "kotlin"))

        val pyWithHashes = listOf(
            "def tag_post(title, hashtag: str = \"#development #code\", count: int = 1):",
            "    pass"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(pyWithHashes, "tag_post", "python"))
    }

    @Test
    fun testTripleQuotedStringLiteralsWithCommas() {
        val rawJsonCode = listOf(
            "fun initializeClient(",
            "    config: String = \"\"\"{\"host\": \"localhost\", \"port\": 8080, \"retries\": 3}\"\"\",",
            "    enabled: Boolean = true",
            ") {"
        )
        assertEquals(2, ComplexityExtractor.extractParamCount(rawJsonCode, "initializeClient", "kotlin"))
    }

    @Test
    fun testLambdaArrowInsideGenericTypeArguments() {
        val genericLambda = listOf(
            "fun registerDispatchers(",
            "    handlers: Map<String, (Int) -> String>,",
            "    fallback: (Exception) -> Unit,",
            "    timeout: Long = 1000L",
            ") {"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(genericLambda, "registerDispatchers", "kotlin"))
    }

    @Test
    fun testComparisonExpressionsInDefaults() {
        val comparisonCode = listOf(
            "fun sliceWindow(",
            "    source: List<Int>,",
            "    limit: Int = if (min < max) min else max,",
            "    offset: Int = 0",
            ") {"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(comparisonCode, "sliceWindow", "kotlin"))
    }

    @Test
    fun testDeclarationStartMatchingWordBoundary() {
        val codeWithSubstrings = listOf(
            "val targetWidget = 100",
            "val forgetful = true",
            "fun get(key: String, defaultVal: String = \"\"): String {"
        )
        assertEquals(2, ComplexityExtractor.extractParamCount(codeWithSubstrings, "get", "kotlin"))
    }

    @Test
    fun testAnnotationPrecedingFunctionWithFunctionName() {
        val annotatedCode = listOf(
            "@Deprecated(\"Use newOperation instead of operation\")",
            "fun operation(x: Int, y: Int, z: Int): Int {"
        )
        assertEquals(3, ComplexityExtractor.extractParamCount(annotatedCode, "operation", "kotlin"))
    }

    @Test
    fun testSecondaryConstructorExtraction() {
        val secondaryCtor = listOf(
            "class NetworkService {",
            "    constructor(client: HttpClient, cache: Cache, timeout: Int, maxRetries: Int, verbose: Boolean) : this(client) {",
            "    }",
            "}"
        )
        assertEquals(5, ComplexityExtractor.extractParamCount(secondaryCtor, "constructor", "kotlin"))
    }
}
