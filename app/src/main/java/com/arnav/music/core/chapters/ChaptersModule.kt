package com.arnav.music.core.chapters

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Loaded next to `appModule` by `ArnavApp` (keeps AppModule.kt untouched). */
val chaptersModule = module {
    single { ChapterRepository(androidContext(), get(), get(), get()) }
}
