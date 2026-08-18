package com.situ.aichat.di

import android.content.Context
import com.situ.aichat.data.remote.llm.ApiBalanceService
import com.situ.aichat.data.remote.llm.CapabilityDetector
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.modelcatalog.ModelCatalogService
import com.situ.aichat.security.ApiKeyStore
import com.situ.aichat.tts.SystemTtsEngine
import com.situ.aichat.tts.TtsService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false   // omit null fields on the wire (matches iOS encodeIfPresent)
        encodeDefaults = false
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Redirect handling (mirrors iOS RedirectDelegate's security intent): OkHttp follows
        // redirects and keeps Authorization on SAME-host redirects (incl. http→https), but strips
        // Authorization/Cookie on ANY host change — even stricter than iOS's exact-host rule.
        // No cookie jar is set, so cookies are never stored (matches iOS ephemeral session).
        // These are OkHttp defaults; set explicitly to document the guarantee.
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Provides
    @Singleton
    fun provideLlmClient(client: OkHttpClient, json: Json): LlmClient = LlmClient(client, json)

    @Provides
    @Singleton
    fun provideCapabilityDetector(client: OkHttpClient, json: Json): CapabilityDetector =
        CapabilityDetector(client, json)

    @Provides
    @Singleton
    fun provideModelCatalogService(client: OkHttpClient, json: Json): ModelCatalogService =
        ModelCatalogService(client, json)

    @Provides
    @Singleton
    fun provideApiBalanceService(client: OkHttpClient, json: Json): ApiBalanceService =
        ApiBalanceService(client, json)

    @Provides
    @Singleton
    fun provideApiKeyStore(@ApplicationContext context: Context): ApiKeyStore = ApiKeyStore(context)

    @Provides
    @Singleton
    fun provideTtsService(
        client: OkHttpClient,
        json: Json,
        @ApplicationContext context: Context,
        systemEngine: SystemTtsEngine,
    ): TtsService = TtsService(client, json, context, systemEngine)
}
