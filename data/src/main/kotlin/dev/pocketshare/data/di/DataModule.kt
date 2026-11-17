package dev.pocketshare.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.core.security.PasswordHasher
import dev.pocketshare.core.security.TokenGenerator
import dev.pocketshare.core.share.ShareContentSource
import dev.pocketshare.core.share.ShareStore
import dev.pocketshare.data.content.SafShareContentSource
import dev.pocketshare.data.db.PocketShareDatabase
import dev.pocketshare.data.db.ShareDao
import dev.pocketshare.data.db.TrafficSampleDao
import dev.pocketshare.data.log.FileAppLogger
import dev.pocketshare.data.repository.DefaultShareRepository
import dev.pocketshare.data.repository.ShareRepository
import dev.pocketshare.data.settings.AndroidKeystoreSecretCipher
import dev.pocketshare.data.settings.SecretCipher
import java.time.Clock
import javax.inject.Singleton

private val Context.pocketShareDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pocketshare_settings",
)

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindShareStore(impl: DefaultShareRepository): ShareStore

    @Binds
    abstract fun bindShareRepository(impl: DefaultShareRepository): ShareRepository

    @Binds
    abstract fun bindShareContentSource(impl: SafShareContentSource): ShareContentSource

    @Binds
    abstract fun bindAppLogger(impl: FileAppLogger): AppLogger

    @Binds
    abstract fun bindSecretCipher(impl: AndroidKeystoreSecretCipher): SecretCipher

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(
            @ApplicationContext context: Context,
        ): PocketShareDatabase =
            Room
                .databaseBuilder(context, PocketShareDatabase::class.java, "pocketshare.db")
                .addMigrations(PocketShareDatabase.MIGRATION_1_2)
                .build()

        @Provides
        fun provideShareDao(database: PocketShareDatabase): ShareDao = database.shareDao()

        @Provides
        fun provideTrafficDao(database: PocketShareDatabase): TrafficSampleDao = database.trafficDao()

        @Provides
        @Singleton
        fun provideDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.pocketShareDataStore

        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemUTC()

        @Provides
        @Singleton
        fun provideTokenGenerator(): TokenGenerator = TokenGenerator()

        @Provides
        @Singleton
        fun providePasswordHasher(): PasswordHasher = PasswordHasher()
    }
}
