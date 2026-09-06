package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.library.Series
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A series' files → seasons → episodes (#43), against the shapes a real scan of a
 * show leaves in the catalog: one edition per season labelled by the node, the episode
 * number only in the filename, and the sidecars (`.nfo`, `-thumb.jpg`) the scan
 * recorded as their own assets.
 */
class SeriesTest {

    private fun file(
        id: String, name: String, label: String? = null, role: String? = "primary", mime: String? = "video/mp4",
        hash: String? = "blake3:$id", path: String? = null, missing: String? = null,
    ) = WorkAsset(
        id = id, editionId = "e-${label ?: "none"}", role = role, filename = name, mime = mime, blobHash = hash,
        sourcePath = path ?: "/media/shows/Yellowstone (2018)/${label ?: "."}/$name", missingSince = missing,
        editionLabel = label, sizeBytes = 1_500_000_000L,
    )

    private val yellowstone = listOf(
        file("s5e2", "Yellowstone.2018.S05E02.The.Sting.of.Wisdom.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 05"),
        file("s4e2", "Yellowstone.2018.S04E02.Phantom.Pain.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 04"),
        file("s4e1", "Yellowstone.2018.S04E01.Half.the.Money.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 04"),
        file("s4e1-nfo", "Yellowstone.2018.S04E01.Half.the.Money.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.nfo", "Season 04", mime = null, hash = "blake3:nfo"),
        file("s4e1-thumb", "Yellowstone.2018.S04E01.Half.the.Money.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb-thumb.jpg", "Season 04", role = "artwork", mime = "image/jpeg"),
        file("poster", "poster.jpg", "Season 04", role = "artwork", mime = "image/jpeg"),
        file("s5e1", "Yellowstone.2018.S05E01.One.Hundred.Years.Is.Nothing.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 05", missing = "2026-09-01T00:00:00Z"),
        file("sp1", "Yellowstone.S00E01.Behind.the.Story.720p.mp4", "Specials"),
    )

    @Test fun groupsAShowIntoSeasonsThenEpisodesInOrder() {
        val seasons = Series.seasons(yellowstone)
        assertEquals(listOf("Season 4", "Season 5", "Specials"), seasons.map { it.label })
        assertEquals(listOf(4, 5, 0), seasons.map { it.number })

        val s4 = seasons[0]
        assertEquals("sidecars are not episodes", listOf("s4e1", "s4e2"), s4.episodes.map { it.asset.id })
        assertEquals("S04E01", s4.episodes[0].code)
        assertEquals("Half the Money", s4.episodes[0].title)
        assertEquals("S04E01 Half the Money", s4.episodes[0].label)
        assertEquals("Phantom Pain", s4.episodes[1].title)

        val s5 = seasons[1]
        assertEquals(listOf("s5e1", "s5e2"), s5.episodes.map { it.asset.id })
        assertEquals("One Hundred Years Is Nothing", s5.episodes[0].title)
        assertFalse("a missing file is listed but cannot play", s5.episodes[0].isPlayable)
        assertEquals("The Sting of Wisdom", s5.episodes[1].title)

        assertEquals("Behind the Story", seasons[2].episodes.single().title)
        assertEquals("S00E01", seasons[2].episodes.single().code)
    }

    @Test fun theHeaderPlayIsTheFirstPlayableEpisodeInSeasonOrder() {
        val seasons = Series.seasons(yellowstone)
        assertEquals("s4e1", Series.firstPlayable(seasons)?.asset?.id)
        val show = Work(id = "w", title = "Yellowstone", kind = "series")
        assertEquals("Yellowstone — S04E01 Half the Money", Series.playTitle(show, Series.firstPlayable(seasons)!!))
        assertNull(Series.firstPlayable(Series.seasons(listOf(file("m", "Show.S01E01.mkv", "Season 01", missing = "2026-01-01T00:00:00Z")))))
    }

    @Test fun readsTheOtherFilenameShapesTheNodeAccepts() {
        // "Series/Season 2/05 - Title.mkv": only the directory knows the season, the name leads with the number.
        val dirSeason = Series.episode(file("a", "05 - Home.mkv", label = "Season 02"))!!
        assertEquals(2, dirSeason.season); assertEquals(5, dirSeason.number); assertEquals("Home", dirSeason.title)
        assertEquals("S02E05", dirSeason.code)

        // "2x05": the alternative marker.
        val nxnn = Series.episode(file("b", "The Expanse - 2x05 - Home.mkv", label = null, path = "/m/The Expanse (2015)/Season 02/The Expanse - 2x05 - Home.mkv"))!!
        assertEquals(2, nxnn.season); assertEquals(5, nxnn.number); assertEquals("Home", nxnn.title)

        // "Episode 5 - Title" under a season directory, with no edition label at all.
        val word = Series.episode(file("c", "Episode 5 - Title.mkv", label = null, path = "/m/Show/Season 2/Episode 5 - Title.mkv"))!!
        assertEquals("the season directory on the source path", 2, word.season); assertEquals(5, word.number); assertEquals("Title", word.title)

        // A multi-episode file keeps the first number; a scene name after the code is metadata, not a title.
        val multi = Series.episode(file("d", "Show.s02e05e06.1080p.mkv", label = "Season 02"))!!
        assertEquals(5, multi.number); assertNull(multi.title); assertEquals("S02E05", multi.label)

        // A specials directory, an unlabelled edition.
        val special = Series.episode(file("e", "S00E03.Making.Of.mkv", label = "", path = "/m/Show/Specials/S00E03.Making.Of.mkv"))!!
        assertEquals(0, special.season); assertEquals("Making Of", special.title)

        // Bracketed tags and a year are cut; a release group trailing the codec goes with it.
        assertEquals("Pilot", Series.episodeTitle(".Pilot.[1080p].x265-GRP"))
        assertEquals("The Long Night", Series.episodeTitle(" - The Long Night (2019) 2160p HDR"))
        assertNull(Series.episodeTitle(".PROPER.1080p.WEB.H264-GROUP"))
    }

    @Test fun aFileWithNoMarkerIsStillAnEpisodeUnderItsSeason() {
        val eps = Series.seasons(listOf(
            file("x", "Finale.mkv", label = "Season 03"),
            file("y", "S03E01.mkv", label = "Season 03"),
        ))
        assertEquals(1, eps.size)
        assertEquals("numbered first, then the unnumbered by name", listOf("y", "x"), eps[0].episodes.map { it.asset.id })
        assertEquals("Finale", eps[0].episodes[1].label)
        assertNull("no number, no code — the season is already the heading", eps[0].episodes[1].code)
    }

    @Test fun aFilmAndAnEmptyOrSidecarOnlyListAreNotSeasons() {
        assertTrue(Series.isSeries("series")); assertTrue(Series.isSeries("show")); assertTrue(Series.isSeries("TV"))
        assertFalse(Series.isSeries("movie")); assertFalse(Series.isSeries(null))
        assertTrue(Series.seasons(emptyList()).isEmpty())
        assertTrue(Series.seasons(listOf(file("p", "poster.jpg", "Season 01", role = "artwork", mime = "image/jpeg"))).isEmpty())
        // Files with no season anywhere still list, under "Episodes", last.
        val s = Series.seasons(listOf(file("q", "Show.S01E01.mkv", label = null, path = "/m/Show/Show.S01E01.mkv"), file("r", "extra.mkv", label = null, path = "/m/Show/extra.mkv")))
        assertEquals(listOf("Season 1", "Episodes"), s.map { it.label })
    }

    @Test fun seasonLabelsAndDirectoriesRead() {
        assertEquals(2, Series.seasonOfLabel("Season 02")); assertEquals(0, Series.seasonOfLabel("Specials"))
        assertNull(Series.seasonOfLabel("1080p BluRay")); assertNull(Series.seasonOfLabel(null))
        assertEquals(3, Series.seasonOfPath("/m/Show/Season 3/x.mkv")); assertEquals(0, Series.seasonOfPath("/m/Show/Specials/x.mkv"))
        assertEquals(12, Series.seasonOfPath("/m/Show/Staffel 12 - Finale/x.mkv")); assertNull(Series.seasonOfPath("/m/Show/x.mkv")); assertNull(Series.seasonOfPath(null))
    }
}
