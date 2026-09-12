package com.libvio.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libvio.tv.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveCatalogTest {
    @Test fun publishedHostsCatalogFiltersSearchAndLocalReload()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLibvio")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=LibvioRepository(context)
        assertTrue(repo.ensureHost().startsWith("https://"))
        val home=repo.home();assertEquals(setOf(1,2,3,4,5),home.sections.map{it.category}.toSet())
        assertEquals(home.sections.flatMap{it.movies}.size,home.sections.flatMap{it.movies}.distinctBy{it.id}.size)
        val first=repo.catalog("/show/1-----------.html")
        assertTrue(first.movies.isNotEmpty());assertEquals(5,first.filters.size)
        val year=first.filters.first{it.label.contains("年份")}.options.first{it.label=="2026"}
        val yearPage=repo.catalog(year.path)
        assertTrue(yearPage.filters.first{it.label.contains("年份")}.options.first{it.label=="2026"}.selected)
        val hot=yearPage.filters.first{it.label.contains("排序")}.options.first{it.label=="人气"}
        assertEquals("/show/1--hits---------2026.html",hot.path)
        val ranked=repo.catalog(hot.path);assertTrue(ranked.movies.isNotEmpty())
        val next=repo.catalog(first.nextPath!!);assertTrue(next.movies.any{m->first.movies.none{it.id==m.id}})
        val search=repo.catalog("/search/-------------.html",query="流浪地球")
        assertTrue(search.movies.any{it.id==5811477L})
        val movie=repo.detail(search.movies.first{it.id==5811477L})
        assertTrue(movie.sources.size>=2);assertTrue(movie.sources.none{it.downloadable})
        assertTrue(movie.description.isNotBlank());assertTrue(movie.movie.image.startsWith("https://"))
        val previous=repo.host.value
        assertTrue(repo.hosts.value.any{it!=previous&&it in setOf("https://www.libvio.cam","https://www.libvio.lat")})
        repo.hosts.value.firstOrNull{it!=previous&&it in setOf("https://www.libvio.cam","https://www.libvio.lat")}?.let{mirror->repo.chooseHost(mirror);assertEquals(movie.movie.title,repo.detail(movie.movie).movie.title);repo.chooseHost(previous)}
        val library=LocalLibrary(context);library.load()
        assertTrue(library.records.value.any{it.movie.id==5813548L&&it.positionMs>30_000})
        library.close()
    }
}
