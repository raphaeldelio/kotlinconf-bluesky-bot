package dev.raphaeldelio

import ai.djl.Model
import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.modality.nlp.translator.ZeroShotClassificationInput
import ai.djl.modality.nlp.translator.ZeroShotClassificationOutput
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDArrays
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.translate.*
import java.util.*

class CustomZeroShotClassificationTranslator private constructor(
    private val tokenizer: HuggingFaceTokenizer,
    private val int32: Boolean
) : NoBatchifyTranslator<ZeroShotClassificationInput, ZeroShotClassificationOutput> {

    private lateinit var predictor: Predictor<NDList, NDList>

    override fun prepare(ctx: TranslatorContext) {
        val model: Model = ctx.model
        predictor = model.newPredictor(NoopTranslator(null))
        ctx.predictorManager.attachInternal(UUID.randomUUID().toString(), predictor)
    }

    override fun processInput(ctx: TranslatorContext, input: ZeroShotClassificationInput): NDList {
        ctx.setAttachment("input", input)
        return NDList()
    }

    override fun processOutput(ctx: TranslatorContext, list: NDList): ZeroShotClassificationOutput {
        val input = ctx.getAttachment("input") as ZeroShotClassificationInput

        val template = input.hypothesisTemplate
        val candidates = input.candidates ?: throw TranslateException("Missing candidates in input")

        val manager: NDManager = ctx.ndManager
        val output = NDList(candidates.size)

        for (candidate in candidates) {
            val hypothesis = applyTemplate(template, candidate)
            val encoding: Encoding = tokenizer.encode(input.text, hypothesis)
            val encoded = encoding.toNDList(manager, true, true)
            val batch = Batchifier.STACK.batchify(arrayOf(encoded))
            output.add(predictor.predict(batch)[0])
        }

        var logits: NDArray = NDArrays.concat(output)
        logits = if (input.isMultiLabel) {
            val entailmentId = 0
            val contradictionId = 2
            val scores = NDList()
            for (i in 0 until output.size) {
                val logits2 = output[i]
                val pair = logits2.get(":, {}", manager.create(intArrayOf(contradictionId, entailmentId)))
                val probs = pair.softmax(1)
                val entailmentScore = probs.get(":, 1") // shape: [1]
                scores.add(entailmentScore)
            }
             NDArrays.stack(scores).squeeze()
        } else {
            val entailmentId = 0
            val entailLogits = logits.get(":, $entailmentId")
            val exp = entailLogits.exp()
            val sum = exp.sum()
            exp.div(sum)
        }

        val indices = logits.argSort(-1, false).toLongArray()
        val probabilities = logits.toFloatArray()

        val labels = Array(candidates.size) { "" }
        val scores = DoubleArray(candidates.size)

        for (i in labels.indices) {
            val index = indices[i].toInt()
            labels[i] = candidates[index]
            scores[i] = probabilities[index].toDouble()
        }

        return ZeroShotClassificationOutput(input.text, labels, scores)
    }

    private fun applyTemplate(template: String, arg: String): String {
        val pos = template.indexOf("{}")
        return if (pos == -1) template + arg else template.substring(0, pos) + arg + template.substring(pos + 2)
    }

    companion object {
        fun builder(tokenizer: HuggingFaceTokenizer): Builder = Builder(tokenizer)

        fun builder(tokenizer: HuggingFaceTokenizer, arguments: MutableMap<String, *>): Builder =
            builder(tokenizer).apply { configure(arguments) }
    }

    class Builder(private val tokenizer: HuggingFaceTokenizer) {
        private var int32: Boolean = false

        fun optInt32(int32: Boolean) = apply { this.int32 = int32 }

        fun configure(arguments: MutableMap<String, *>) {
            optInt32(ArgumentsUtil.booleanValue(arguments, "int32"))
        }

        fun build(): CustomZeroShotClassificationTranslator = CustomZeroShotClassificationTranslator(tokenizer, int32)
    }
}