package com.armsone.nasfinder.ui

import com.hanai.core.ImagePairDistance
import com.hanai.core.ImageSelectionRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSelectionPolicyTest {
    private fun photo(id: Int, sharpness: Double = .5, exposure: Double = .5, pixels: Int = 1_000_000) = AnalyzedPhoto(
        photo = StagedPhoto(id, File("p$id.jpg"), "p$id.jpg", "content://x/$id"),
        sharpness = sharpness,
        exposure = exposure,
        pixelCount = pixels,
        aspectRatio = 1.5,
        metrics = PhotoSelectionMetrics(sharpness, exposure, IntArray(256)),
        embedding = null,
    )

    private fun analysis(photos: List<AnalyzedPhoto>, distances: List<ImagePairDistance> = emptyList()) = PhotoSelectionAnalysis(
        batch = PhotoSelectionBatch("00000000-0000-0000-0000-000000000000", photos.map { it.photo }, 0, emptyList()),
        analyzed = photos,
        failures = emptyList(),
        distances = distances,
        usedDino = false,
    )

    @Test
    fun noEligiblePhotoBlocksTheRecommendationInsteadOfInventingOne() {
        assertNull(PhotoSelectionPolicy.recommend(analysis(emptyList())))
    }

    @Test
    fun recommendationEqualsSceneGroupsMinusMarkedlyLowQualityRepresentatives() {
        val good = (0 until 4).map { photo(it) }
        val recommendation = PhotoSelectionPolicy.recommend(analysis(good))
        assertNotNull(recommendation)
        assertEquals(4, recommendation!!.eligible)
        assertEquals(4, recommendation.groups)
        assertEquals(4, recommendation.recommended)

        val withDark = good + photo(4, exposure = 0.0)
        val reduced = PhotoSelectionPolicy.recommend(analysis(withDark))!!
        assertEquals(5, reduced.groups)
        assertEquals(1, reduced.lowQualityRepresentatives)
        assertEquals(4, reduced.recommended)
    }

    @Test
    fun similarScenesAreGroupedAndSelectionNeverExceedsOrShrinksTheRequestedCount() {
        val photos = (0 until 4).map { photo(it, sharpness = .5 - it * .01) }
        val distances = listOf(ImagePairDistance(0, 1, 0.1), ImagePairDistance(0, 2, 0.1), ImagePairDistance(1, 2, 0.1))
        val data = analysis(photos, distances)
        val recommendation = PhotoSelectionPolicy.recommend(data)!!
        assertEquals(2, recommendation.groups)
        assertEquals(2, recommendation.recommended)
        val chosen = PhotoSelectionPolicy.select(data, 3, recommendation)
        assertEquals(3, chosen.selectedIndices.size)
        assertTrue(chosen.includesSimilarExtras)
        assertEquals(1, PhotoSelectionPolicy.select(data, 1, recommendation).selectedIndices.size)
        assertEquals(4, PhotoSelectionPolicy.select(data, 4, recommendation).selectedIndices.size)
        // More than the eligible count is an error for the user to fix, never silently reduced.
        assertTrue(runCatching { PhotoSelectionPolicy.select(data, 5, recommendation) }.isFailure)
    }

    @Test
    fun regressionLowQualityRepresentativeWithHigherScoreThanNormalRepresentativeIsNotChosenFirst() {
        // Photo 0: normal photo (sharpness 0.15 > 0.02, exposure 0.50 -> Q ~ 0.298)
        // Photo 1: blurry photo (sharpness 0.002 < 0.02, exposure 0.95 -> Q ~ 0.328)
        // Photo 1 has a higher raw score Q than Photo 0, but Photo 1 is low-quality.
        val normal = photo(0, sharpness = 0.15, exposure = 0.50)
        val blurryHighExposure = photo(1, sharpness = 0.002, exposure = 0.95)
        val data = analysis(listOf(normal, blurryHighExposure))
        val rec = PhotoSelectionPolicy.recommend(data)!!

        assertEquals(2, rec.groups)
        assertEquals(1, rec.lowQualityRepresentatives)
        assertEquals(1, rec.recommended)
        assertEquals(setOf(1), rec.lowQualityIndices)

        // When N=1, the normal-quality representative (Photo 0) MUST be selected, not the blurry one.
        val chosen1 = PhotoSelectionPolicy.select(data, 1, rec)
        assertEquals(listOf(0), chosen1.selectedIndices)
        assertEquals(ImageSelectionRole.GROUP_REPRESENTATIVE, chosen1.roleByIndex[0])
        assertFalse(chosen1.includesSimilarExtras)

        // When user explicitly requests N=2, both are selected, preserving input order.
        val chosen2 = PhotoSelectionPolicy.select(data, 2, rec)
        assertEquals(listOf(0, 1), chosen2.selectedIndices)
        assertEquals(ImageSelectionRole.GROUP_REPRESENTATIVE, chosen2.roleByIndex[0])
        assertEquals(ImageSelectionRole.GROUP_REPRESENTATIVE, chosen2.roleByIndex[1])
        assertFalse(chosen2.includesSimilarExtras)
    }

    @Test
    fun allLowQualityRepresentativesRecommendOneAndKeepExplicitUserCount() {
        val low1 = photo(0, sharpness = 0.005, exposure = 0.30)
        val low2 = photo(1, sharpness = 0.008, exposure = 0.40)
        val data = analysis(listOf(low1, low2))
        val rec = PhotoSelectionPolicy.recommend(data)!!

        assertTrue(rec.allLowQuality)
        assertEquals(1, rec.recommended)
        assertEquals(2, rec.groups)
        assertEquals(2, rec.lowQualityRepresentatives)

        // N=1 selects the best among low-quality representatives (Photo 1 has higher score)
        val chosen1 = PhotoSelectionPolicy.select(data, 1, rec)
        assertEquals(listOf(1), chosen1.selectedIndices)
        assertEquals(ImageSelectionRole.GROUP_REPRESENTATIVE, chosen1.roleByIndex[1])

        // N=2 preserves explicit count
        val chosen2 = PhotoSelectionPolicy.select(data, 2, rec)
        assertEquals(listOf(0, 1), chosen2.selectedIndices)
    }

    @Test
    fun boundaryConditionsM0AndCountOutOfBoundsThrow() {
        val photos = (0 until 3).map { photo(it) }
        val data = analysis(photos)
        val rec = PhotoSelectionPolicy.recommend(data)!!

        assertTrue(runCatching { PhotoSelectionPolicy.select(data, 0, rec) }.isFailure)
        assertTrue(runCatching { PhotoSelectionPolicy.select(data, -1, rec) }.isFailure)
        assertTrue(runCatching { PhotoSelectionPolicy.select(data, 4, rec) }.isFailure)
    }

    @Test
    fun inputOrderAndRolesPreservedAcrossExplicitCounts() {
        val photos = (0 until 5).map { photo(it, sharpness = 0.4 - it * 0.02) }
        // 0, 1 are in group A; 2 is in group B; 3, 4 are in group C -> 3 groups total
        val distances = listOf(
            ImagePairDistance(0, 1, 0.1),
            ImagePairDistance(3, 4, 0.1),
        )
        val data = analysis(photos, distances)
        val rec = PhotoSelectionPolicy.recommend(data)!!
        assertEquals(3, rec.groups)

        for (n in 1..photos.size) {
            val outcome = PhotoSelectionPolicy.select(data, n, rec)
            assertEquals(n, outcome.selectedIndices.size)
            assertEquals(outcome.selectedIndices.sorted(), outcome.selectedIndices)
            if (n <= 3) {
                assertFalse(outcome.includesSimilarExtras)
                assertTrue(outcome.roleByIndex.values.all { it == ImageSelectionRole.GROUP_REPRESENTATIVE })
            } else {
                assertTrue(outcome.includesSimilarExtras)
                assertEquals(3, outcome.roleByIndex.values.count { it == ImageSelectionRole.GROUP_REPRESENTATIVE })
                assertEquals(n - 3, outcome.roleByIndex.values.count { it == ImageSelectionRole.EXTRA_GROUP_MEMBER })
            }
        }
    }
}
