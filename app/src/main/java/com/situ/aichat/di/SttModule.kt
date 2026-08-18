package com.situ.aichat.di

import com.situ.aichat.stt.SherpaSttEngine
import com.situ.aichat.stt.SttEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the sherpa-onnx engine as the on-device [SttEngine] (P10.1d). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SttModule {
    @Binds
    abstract fun bindSttEngine(impl: SherpaSttEngine): SttEngine
}
