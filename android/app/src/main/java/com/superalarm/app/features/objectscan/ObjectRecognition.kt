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
  private val requiredFrames: Int = REQUIRED_MATCHES,
  private val fallbackThreshold: Double = threshold - FALLBACK_THRESHOLD_DELTA,
  private val fallbackRequiredFrames: Int = requiredFrames + FALLBACK_MATCHES,
) {
  private val references = targets.map { target ->
    TargetReferences(target, target.embeddings.mapNotNull(ObjectEmbeddings::decode))
  }.filter { it.embeddings.isNotEmpty() }

  private val recentMatches = ArrayDeque<TimedMatch>()

  fun accept(encodedEmbedding: String, frameAt: Long): ObjectMatchProgress {
    val embedding = ObjectEmbeddings.decode(encodedEmbedding)
      ?: return ObjectMatchProgress(null, null, 0.0, 0, requiredFrames)
    val best = references
      .map { target ->
        val similarity = target.embeddings.maxOf { ObjectEmbeddings.cosineSimilarity(embedding, it) }
        TargetScore(target.target, similarity)
      }
      .maxByOrNull(TargetScore::similarity)

    if (best == null || best.similarity < fallbackThreshold) {
      recordMatch(null, best?.similarity ?: 0.0, frameAt)
      return ObjectMatchProgress(null, null, best?.similarity ?: 0.0, 0, requiredFrames)
    }

    recordMatch(best.target.id, best.similarity, frameAt)
    val strongMatch = best.similarity >= threshold
    val acceptedThreshold = if (strongMatch) threshold else fallbackThreshold
    val acceptedFrames = if (strongMatch) requiredFrames else fallbackRequiredFrames
    val matchingFrames = recentMatches.takeLast(acceptedFrames + MATCH_WINDOW_PADDING).count {
      it.targetId == best.target.id && it.similarity >= acceptedThreshold
    }
    return ObjectMatchProgress(
      targetId = best.target.id,
      targetLabel = best.target.label,
      similarity = best.similarity,
      matchingFrames = matchingFrames,
      requiredFrames = acceptedFrames,
    )
  }

  fun reset() {
    recentMatches.clear()
  }

  private fun recordMatch(targetId: String?, similarity: Double, frameAt: Long) {
    recentMatches.addLast(TimedMatch(targetId, similarity, frameAt))
    while (
      recentMatches.size > maxOf(requiredFrames, fallbackRequiredFrames) + MATCH_WINDOW_PADDING ||
      frameAt - recentMatches.first().frameAt > MAX_MATCH_WINDOW_MILLIS
    ) {
      recentMatches.removeFirst()
    }
  }

  private data class TargetReferences(val target: ObjectTarget, val embeddings: List<ByteArray>)
  private data class TargetScore(val target: ObjectTarget, val similarity: Double)
  private data class TimedMatch(val targetId: String?, val similarity: Double, val frameAt: Long)

  companion object {
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.72
    const val REQUIRED_MATCHES = 2
    private const val FALLBACK_THRESHOLD_DELTA = 0.08
    private const val FALLBACK_MATCHES = 2
    private const val MATCH_WINDOW_PADDING = 1
    private const val MAX_MATCH_WINDOW_MILLIS = 1_500L
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

  fun selectDiverse(encodedEmbeddings: List<String>, count: Int): List<String> {
    if (encodedEmbeddings.size <= count) return encodedEmbeddings
    val decoded = encodedEmbeddings.mapNotNull { encoded ->
      decode(encoded)?.let { encoded to it }
    }
    if (decoded.size <= count) return decoded.map { it.first }

    val selected = mutableListOf(decoded.first())
    val remaining = decoded.drop(1).toMutableList()
    while (selected.size < count && remaining.isNotEmpty()) {
      val next = remaining.minBy { candidate ->
        selected.maxOf { reference -> cosineSimilarity(candidate.second, reference.second) }
      }
      selected += next
      remaining -= next
    }
    return selected.map { it.first }
  }
}
