package org.gotson.komga.infrastructure.metadata.anilist

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.MetadataPatchTarget
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesMetadataPatch
import org.gotson.komga.infrastructure.metadata.SeriesMetadataProvider
import org.gotson.komga.infrastructure.metadata.anilist.dto.Root
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class AnilistSeriesProvider : SeriesMetadataProvider {
  private val anilistApi = "https://graphql.anilist.co/"
  private val webClient: WebClient = WebClient.create(anilistApi)

  override fun getSeriesMetadata(series: Series): SeriesMetadataPatch? {
    if (series.oneshot) {
      logger.debug { "Disabled for oneshot series, skipping" }
      return null
    }

    val (cleanedTitle, year) = cleanSeriesTitleAndExtractYear(series.name)

    logger.debug { "Fetching AniList metadata for cleaned series title: $cleanedTitle" }

    val query =
      """
      {
        Media(search:"$cleanedTitle", type: MANGA) {
          description
          title {
            romaji
            english
            native
            userPreferred
          }
          status
          startDate {
            year
          }
          genres
          tags {
            name
            isMediaSpoiler
            isGeneralSpoiler
          }
          countryOfOrigin
          coverImage {
            extraLarge
            large
            medium
          }
          isAdult
          siteUrl
          volumes
        }
      }
      """.trimIndent()

    val bodyValue = mapOf("query" to query)

    try {
      val fixedDelayInMillis = 65000L // 65 seconds delay the last 3 request to reset Anilist api rate limit.
      val rateLimitApproachThreshold = 65

      val anilistResponseMono =
        webClient.post()
          .uri("/")
          .bodyValue(bodyValue)
          .exchangeToMono { response ->
            val headers = response.headers().asHttpHeaders()
            val rateLimitRemaining = headers.getFirst("X-RateLimit-Remaining")?.toIntOrNull()
            val retryAfter = headers.getFirst("Retry-After")?.toLongOrNull()

            if (response.statusCode().is2xxSuccessful) {
              logger.debug { "Current Anilist rate limit remaining: $rateLimitRemaining" }

              rateLimitRemaining?.let {
                if (it < rateLimitApproachThreshold) {
                  logger.warn { "Approaching Anilist rate limit, remaining: $it. Waiting for the next minute." }
                  // Wait for the rest of the minute as a simple approach to avoid hitting the limit
                  Thread.sleep(fixedDelayInMillis)
                }
              }
              response.bodyToMono(Root::class.java)
            } else if (response.statusCode().value() == 429 && retryAfter != null) {
              logger.warn { "Anilist Rate limit exceeded, retrying after $retryAfter seconds" }
              Mono.delay(Duration.ofSeconds(retryAfter)).flatMap { Mono.empty() }
            } else {
              logger.error { "Failed to fetch AniList metadata, status code: ${response.statusCode()}" }
              Mono.empty()
            }
          }
          .onErrorResume { e ->
            logger.error { "Error fetching AniList metadata: ${e.message}" }
            Mono.empty()
          }
          .block()

      anilistResponseMono?.let { it ->
        val media = it.data.media
        val titleVariants = listOfNotNull(media.title?.romaji, media.title?.english, media.title?.native, media.title?.userPreferred)
        val cleanedTitleNormalized = normalizeTitle(cleanedTitle)
        val isYearMatch = year != null && media.startDate?.year?.toInt() == year
        val isTitleMatch = titleVariants.any { normalizeTitle(it).equals(cleanedTitleNormalized, ignoreCase = true) }

        if (isYearMatch) {
          logger.debug { "The year matches, saving metadata for series: $cleanedTitle" }
          return convertAnilistMetadataToSeriesProvider(it)
        } else if (isTitleMatch) {
          logger.debug { "Exact title match found among variants, saving metadata for series: $cleanedTitle" }
          return convertAnilistMetadataToSeriesProvider(it)
        } else {
          // Neither year matches nor titles match exactly
          logger.debug { "No matching data found for series: $cleanedTitle" }
        }
      }
    } catch (e: Exception) {
      logger.error { "Exception during AniList metadata fetching: ${e.message}" }
    }

    return null
  }

  override fun shouldLibraryHandlePatch(
    library: Library,
    target: MetadataPatchTarget,
  ): Boolean =
    when (target) {
      MetadataPatchTarget.SERIES -> library.importAnilistSeries
      else -> false
    }

  fun cleanSeriesTitleAndExtractYear(seriesName: String): Pair<String, Int?> {
    // Regex to find all occurrences of parentheses content
    val allParenthesesRegex = "\\s*\\([^)]*\\)".toRegex()
    // Regex to specifically capture the first occurrence of a year within parentheses
    val yearRegex = "\\((\\d{4})\\)".toRegex()

    // Extract the year from the first matching group that looks like a year
    val year = yearRegex.find(seriesName)?.groups?.get(1)?.value?.toInt()

    // Clean the title by removing all parentheses content
    val cleanedTitle = allParenthesesRegex.replace(seriesName, "").trim()

    return Pair(cleanedTitle, year)
  }

  fun convertAnilistMetadataToSeriesProvider(anilistData: Root): SeriesMetadataPatch {
    val anilistMetadata = anilistData.data.media

    val status =
      when (anilistMetadata.status?.uppercase()) {
        "FINISHED" -> SeriesMetadata.Status.ENDED
        "RELEASING" -> SeriesMetadata.Status.ONGOING
        "CANCELLED" -> SeriesMetadata.Status.ABANDONED
        "HIATUS" -> SeriesMetadata.Status.HIATUS

        else -> null
      }

    val genresSet = anilistMetadata.genres?.toSet()
    val tagsSet =
      anilistMetadata.tags
        ?.filter { tag -> tag.isMediaSpoiler != true } // Exclude tags with isMediaSpoiler == true
        ?.mapNotNull { it.name }
        ?.toSet()

    val ageRating = if (anilistMetadata.isAdult == true) 18 else null

    val cleanDescription =
      anilistMetadata.description
        // Converts <br> and <br/> to newline characters
        ?.replace(Regex("<br\\s*/?>"), "\n")
        // Removes remaining HTML tags
        ?.replace(Regex("<[^>]*>"), "")
        // Replaces multiple spaces with a single space
        ?.replace(Regex("\\s+"), " ")
        // Removes any spaces right after new lines
        ?.replace(Regex("\\n "), "\n")
        ?.trim()

    return SeriesMetadataPatch(
      title = anilistMetadata.title?.english?.ifBlank { null },
      titleSort = anilistMetadata.title?.romaji?.ifBlank { anilistMetadata.title.english?.ifBlank { null } },
      status = status,
      summary = cleanDescription?.ifBlank { null },
      readingDirection = null,
      publisher = null,
      ageRating = ageRating,
      language = anilistMetadata.countryOfOrigin?.ifBlank { null },
      genres = genresSet,
      totalBookCount = anilistMetadata.volumes,
      collections = emptySet(),
      tags = tagsSet,
    )
  }

  fun normalizeTitle(title: String): String {
    return title.lowercase()
      .replace(Regex("[^\\w\\s]|_"), "")
      .replace(Regex("\\s+"), " ")
      .trim()
  }
}
