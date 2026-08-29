package dev.nytweetdeck.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.data.DirectMessageRepository
import dev.nytweetdeck.android.data.ListDirectoryRepository
import dev.nytweetdeck.android.data.NotificationRepository
import dev.nytweetdeck.android.data.PostActionRepository
import dev.nytweetdeck.android.data.PostComposerRepository
import dev.nytweetdeck.android.data.PostDetailRepository
import dev.nytweetdeck.android.data.CommunityNoteRepository
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.data.TrendRepository
import dev.nytweetdeck.android.data.UserDirectoryRepository
import dev.nytweetdeck.android.xapi.XSessionVerifier
import java.io.File
import java.nio.file.Path

class DeckViewModelFactory(
    private val settingsPath: Path,
    private val accountStoreFile: File,
    private val sessionVerifier: XSessionVerifier,
    private val timelineRepository: TimelineRepository,
    private val notificationRepository: NotificationRepository,
    private val trendRepository: TrendRepository,
    private val directMessageRepository: DirectMessageRepository,
    private val listDirectoryRepository: ListDirectoryRepository,
    private val userDirectoryRepository: UserDirectoryRepository,
    private val postActionRepository: PostActionRepository,
    private val postComposerRepository: PostComposerRepository,
    private val postDetailRepository: PostDetailRepository,
    private val communityNoteRepository: CommunityNoteRepository,
    private val postTranslationRepository: PostTranslationRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass.isAssignableFrom(DeckViewModel::class.java)) {
            "未対応のViewModelです。"
        }
        @Suppress("UNCHECKED_CAST")
        return DeckViewModel(
            settingsStore = DeckSettingsStore(settingsPath),
            accountStoreFile = accountStoreFile,
            sessionVerifier = sessionVerifier,
            timelineRepository = timelineRepository,
            notificationRepository = notificationRepository,
            trendRepository = trendRepository,
            directMessageRepository = directMessageRepository,
            listDirectoryRepository = listDirectoryRepository,
            userDirectoryRepository = userDirectoryRepository,
            postActionRepository = postActionRepository,
            postComposerRepository = postComposerRepository,
            postDetailRepository = postDetailRepository,
            communityNoteRepository = communityNoteRepository,
            postTranslationRepository = postTranslationRepository,
            adaptiveRefreshIntervalMillis = 60_000L,
        ) as T
    }
}
