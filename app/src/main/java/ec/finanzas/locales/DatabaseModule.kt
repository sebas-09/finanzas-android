package ec.finanzas.locales

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ec.finanzas.database.BootstrapDatabase
import ec.finanzas.database.EncryptedDatabaseFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): BootstrapDatabase =
        EncryptedDatabaseFactory.open(context)
}

