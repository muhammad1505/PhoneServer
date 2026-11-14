package dev.pocketshare.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pocketshare.tunnel.CloudflaredRuntime
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideCloudflaredRuntime(
        @ApplicationContext context: Context,
    ): CloudflaredRuntime = CloudflaredRuntime(context)

    @Provides
    @Singleton
    fun provideSecureRandom(): SecureRandom = SecureRandom()
}
