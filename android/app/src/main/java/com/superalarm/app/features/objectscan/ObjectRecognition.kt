package com.superalarm.app.features.objectscan

import com.superalarm.app.data.ObjectTarget
import java.util.Base64
import kotlin.math.sqrt

data class ObjectMatchProgress(
  val targetId: String?,
  val targetLabel: String?,
  val similarity: Double,
  val matchingFrames: Int,
  val requiredFrames: Int,
) {
  val accepted: Boolean
    get() = targetId != null && matchingFrames >= requiredFrames
}

class ObjectMatchTracker(
  targets: List<ObjectTarget>,
  private val threshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
  private val requiredFrames: Int = REQUIRED_MATCHING_FRAMES,
) {
  private val references = targets.map { target ->
    TargetReferences(target, target.embeddings.mapNotNull(ObjectEmbeddings::decode))
  }.filter { it.embeddings.isNotEmpty() }

  private var candidateId: String? = null
  private var matchingFrames = 0
  private var lastFrameAt = 0L

  fun accept(encodedEmbedding: String, frameAt: Long): ObjectMatchProgress {
    val embedding = ObjectEmbeddings.decode(encodedEmbedding)
      ?: return ObjectMatchProgress(null, null, 0.0, 0, requiredFrames)
    val best = references
      .map { target ->
        val similarity = target.embeddings.maxOf { ObjectEmbeddings.cosineSimilarity(embedding, it) }
        TargetScore(target.target, similarity)
      }
      .maxByOrNull(TargetScore::similarity)

    if (best == null || best.similarity < threshold) {
      reset()
      return ObjectMatchProgress(null, null, best?.similarity ?: 0.0, 0, requiredFrames)
    }

    val continuous = candidateId == best.target.id && frameAt - lastFrameAt <= MAX_MATCH_GAP_MILLIS
    matchingFrames = if (continuous) matchingFrames + 1 else 1
    candidateId = best.target.id
    lastFrameAt = frameAt
    return ObjectMatchProgress(
      targetId = best.target.id,
      targetLabel = best.target.label,
      similarity = best.similarity,
      matchingFrames = matchingFrames,
      requiredFrames = requiredFrames,
    )
  }

  fun reset() {
    candidateId = null
    matchingFrames = 0
    lastFrameAt = 0L
  }

  private data class TargetReferences(val target: ObjectTarget, val embeddings: List<ByteArray>)
  private data class TargetScore(val target: ObjectTarget, val similarity: Double)

  companion object {
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.72
    const val REQUIRED_MATCHING_FRAMES = 4
    private const val MAX_MATCH_GAP_MILLIS = 1_500L
  }
}

object ObjectEmbeddings {
  fun encode(embedding: ByteArray): String = Base64.getEncoder().encodeToString(embedding)

  fun decode(encoded: String): ByteArray? = runCatching {
    Base64.getDecoder().decode(encoded)
  }.getOrNull()?.takeIf { it.isNotEmpty() }

  fun cosineSimilarity(first: String, second: String): Double {
    val firstEmbedding = decode(first) ?: return 0.0
    val secondEmbedding = decode(second) ?: return 0.0
    return cosineSimilarity(firstEmbedding, secondEmbedding)
  }

  fun cosineSimilarity(first: ByteArray, second: ByteArray): Double {
    if (first.size != second.size || first.isEmpty()) return 0.0
    var dot = 0.0
    var firstMagnitude = 0.0
    var secondMagnitude = 0.0
    first.indices.forEach { index ->
      val firstValue = first[index].toDouble()
      val secondValue = second[index].toDouble()
      dot += firstValue * secondValue
      firstMagnitude += firstValue * firstValue
      secondMagnitude += secondValue * secondValue
    }
    if (firstMagnitude == 0.0 || secondMagnitude == 0.0) return 0.0
    return dot / sqrt(firstMagnitude * secondMagnitude)
  }
}
