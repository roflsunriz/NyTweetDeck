package dev.nytweetdeck.android

import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.model.ComposerPoll
import dev.nytweetdeck.android.xapi.XApiEnvironment
import dev.nytweetdeck.android.xapi.XSessionCredentials
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveComposerWebSmokeTest {
    @Test
    fun uploadsAnUnpostedImageAndCreatesAnUnpublishedPollCard() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mediaUploadAuthorized") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountFile = File(context.noBackupFilesDir, "accounts/accounts.json")
        assumeTrue(accountFile.isFile)
        val account = AccountStore(accountFile).selectedAccount()
            ?: error("保存済みのXアカウントがありません。")
        val environment = XApiEnvironment(context) {
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken)
        }
        val client = environment.composerWebClient()
        val fixture = File(context.cacheDir, "composer-smoke.png")
        val gif = File(context.cacheDir, "composer-smoke.gif")
        try {
            fixture.writeBytes(Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
                Base64.DEFAULT,
            ))
            assertTrue(client.upload(account, Uri.fromFile(fixture)).matches(Regex("[0-9]{1,30}")))
            gif.writeBytes(Base64.decode(
                "R0lGODlhEAAQAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAEUAAEALAAAAAAQABAAgQAA/wAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAOw==",
                Base64.DEFAULT,
            ))
            assertTrue(client.upload(account, Uri.fromFile(gif)).matches(Regex("[0-9]{1,30}")))
            assertTrue(client.createPoll(account, ComposerPoll(listOf("Option A", "Option B"), 60)).isNotBlank())
            assertTrue(client.searchPlaces(account, "Tokyo").isNotEmpty())
        } finally {
            fixture.delete()
            gif.delete()
        }
    }
}
