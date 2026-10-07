package com.arnav.music.core.metadata

import com.arnav.music.BuildConfig
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.feature.album.AlbumViewModel
import com.arnav.music.feature.credits.CreditsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Credits, album pages and auto-tagging. Loaded next to `appModule` by `ArnavApp`
 * (`modules(appModule, chaptersModule, metadataModule)`), keeping AppModule.kt untouched.
 */
val metadataModule = module {
    single { MusicBrainzClient(get(), MusicBrainzClient.userAgent(BuildConfig.VERSION_NAME)) }
    single { AutoTagger(androidContext(), get<ArnavDatabase>().tagOverrides(), get(), get()) }
    single { CreditsRepository(androidContext(), get(), get(), get()) }
    viewModel { CreditsViewModel(get(), get(), get()) }
    viewModel { AlbumViewModel(get()) }
}
