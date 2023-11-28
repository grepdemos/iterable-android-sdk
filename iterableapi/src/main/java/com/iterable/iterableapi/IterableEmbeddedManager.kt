package com.iterable.iterableapi

import android.content.Context
import com.iterable.iterableapi.IterableHelper.SuccessHandler
import org.json.JSONException
import org.json.JSONObject

public class IterableEmbeddedManager : IterableActivityMonitor.AppStateCallback {

    // region constants
    val TAG = "IterableEmbeddedManager"
    // endregion

    // region variables
    private var localMessages: List<IterableEmbeddedMessage> = ArrayList()
    private var updateHandleListeners = mutableListOf<IterableEmbeddedUpdateHandler>()
    private var iterableApi: IterableApi
    private var context: Context

    private var embeddedSessionManager = EmbeddedSessionManager()

    private var activityMonitor: IterableActivityMonitor? = null

    // endregion

    // region constructor

    //Constructor of this class with actionHandler and updateHandler
    public constructor(
        iterableApi: IterableApi
    ) {
        this.iterableApi = iterableApi
        this.context = iterableApi.mainActivityContext
        activityMonitor = IterableActivityMonitor.getInstance()
        activityMonitor?.addCallback(this)
    }

    // endregion

    // region getters and setters

    //Add updateHandler to the list
    public fun addUpdateListener(updateHandler: IterableEmbeddedUpdateHandler) {
        updateHandleListeners.add(updateHandler)
    }

    //Remove updateHandler from the list
    public fun removeUpdateListener(updateHandler: IterableEmbeddedUpdateHandler) {
        updateHandleListeners.remove(updateHandler)
        embeddedSessionManager.endSession()
    }

    //Get the list of updateHandlers
    public fun getUpdateHandlers(): List<IterableEmbeddedUpdateHandler> {
        return updateHandleListeners
    }

    public fun getEmbeddedSessionManager(): EmbeddedSessionManager {
        return embeddedSessionManager
    }

    // endregion

    // region public methods

    //Gets the list of embedded messages in memory without syncing
    fun getMessages(): List<IterableEmbeddedMessage> {
        return localMessages
    }

    fun reset() {
        val emptyMessages = listOf<IterableEmbeddedMessage>()
        updateLocalMessages(emptyMessages)
    }

    //Network call to get the embedded messages
    fun syncMessages() {
        IterableLogger.v(TAG, "Syncing messages...")

        IterableApi.sharedInstance.getEmbeddedMessages(SuccessHandler { data ->
            IterableLogger.v(TAG, "Got response from network call to get embedded messages")
            try {
                val remoteMessageList: MutableList<IterableEmbeddedMessage> = ArrayList()

                val placementArray = data.optJSONArray(IterableConstants.ITERABLE_EMBEDDED_MESSAGE_PLACEMENTS)
                val placement = placementArray.getJSONObject(0)
                val messagesArray = placement.optJSONArray(IterableConstants.ITERABLE_EMBEDDED_MESSAGE)

                if (messagesArray != null) {
                    for (i in 0 until messagesArray.length()) {
                        val messageJson = messagesArray.optJSONObject(i)
                        val message = IterableEmbeddedMessage.fromJSONObject(messageJson)
                        remoteMessageList.add(message)
                    }
                } else {
                    IterableLogger.e(
                        TAG,
                        "Array not found in embedded message response. Probably a parsing failure"
                    )
                }
                updateLocalMessages(remoteMessageList)
                IterableLogger.v(TAG, "$localMessages")

            } catch (e: JSONException) {
                IterableLogger.e(TAG, e.toString())
            }
        }, object : IterableHelper.FailureHandler {
            override fun onFailure(reason: String, data: JSONObject?) {
                if (reason.equals(
                        "SUBSCRIPTION_INACTIVE",
                        ignoreCase = true
                    ) || reason.equals("Invalid API Key", ignoreCase = true)
                ) {
                    IterableLogger.e(TAG, "Subscription is inactive. Stopping sync")
                    broadcastSubscriptionInactive()
                    return
                } else {
                    //TODO: Instead of generic condition, have the retry only in certain situation
                    IterableLogger.e(TAG, "Error while fetching embedded messages: $reason")
                }
            }
        })
    }

    fun handleEmbeddedClick(message: IterableEmbeddedMessage, buttonIdentifier: String?, clickedUrl: String?) {
        if ((clickedUrl != null) && clickedUrl.toString().isNotEmpty()) {
            if (clickedUrl.startsWith(IterableConstants.URL_SCHEME_ACTION)) {
                // This is an action:// URL, pass that to the custom action handler
                val actionName: String = clickedUrl.replace(IterableConstants.URL_SCHEME_ACTION, "")
                IterableLogger.d("IterableEmbeddedManager", "ACTION NAME: " + actionName)
                IterableActionRunner.executeAction(
                    context,
                    IterableAction.actionCustomAction(actionName),
                    IterableActionSource.EMBEDDED
                )
            } else if (clickedUrl.startsWith(IterableConstants.URL_SCHEME_ITBL)) {
                // Handle itbl:// URLs, pass that to the custom action handler for compatibility
                val actionName: String = clickedUrl.replace(IterableConstants.URL_SCHEME_ITBL, "")
                IterableActionRunner.executeAction(
                    context,
                    IterableAction.actionCustomAction(actionName),
                    IterableActionSource.EMBEDDED
                )
            } else {
                IterableActionRunner.executeAction(
                    context,
                    IterableAction.actionOpenUrl(clickedUrl),
                    IterableActionSource.EMBEDDED
                )
            }
        }
    }

    private fun broadcastSubscriptionInactive() {
        updateHandleListeners.forEach {
            IterableLogger.d(TAG, "Broadcasting subscription inactive to the views")
            it.onEmbeddedMessagingDisabled()
        }
    }

    private fun updateLocalMessages(remoteMessageList: List<IterableEmbeddedMessage>) {
        IterableLogger.printInfo()
        var localMessagesChanged = false

        // Get local messages in a mutable list
        val localMessageList = getMessages().toMutableList()
        val localMessageMap = mutableMapOf<String, IterableEmbeddedMessage>()
        localMessageList.forEach {
            localMessageMap[it.metadata.messageId] = it
        }

        // Check for new messages and add them to the local list
        remoteMessageList.forEach {
            if (!localMessageMap.containsKey(it.metadata.messageId)) {
                localMessagesChanged = true
                localMessageList.add(it)
                IterableApi.getInstance().trackEmbeddedMessageReceived(it)
            }
        }

        // Check for messages in the local list that are not in the remote list and remove them
        val remoteMessageMap = mutableMapOf<String, IterableEmbeddedMessage>()
        remoteMessageList.forEach {
            remoteMessageMap[it.metadata.messageId] = it
        }
        val messagesToRemove = mutableListOf<IterableEmbeddedMessage>()
        localMessageList.forEach {
            if (!remoteMessageMap.containsKey(it.metadata.messageId)) {
                messagesToRemove.add(it)

                //TODO: Make a call to the updateHandler to notify that the message has been removed
                //TODO: Make a call to backend if needed
                localMessagesChanged = true
            }
        }
        localMessageList.removeAll(messagesToRemove)

        this.localMessages = localMessageList

        if (localMessagesChanged) {
            updateHandleListeners.forEach {
                IterableLogger.d(TAG, "Calling updateHandler")
                it.onMessagesUpdated()
            }
        }
    }
    // endregion

    override fun onSwitchToForeground() {
        IterableLogger.printInfo()
        embeddedSessionManager.startSession()
        IterableLogger.d(TAG, "Calling start session")
        syncMessages()
    }

    override fun onSwitchToBackground() {
        embeddedSessionManager.endSession()
    }
}

// region interfaces

public interface IterableEmbeddedUpdateHandler {
    fun onMessagesUpdated()
    fun onEmbeddedMessagingDisabled()
}

// endregion

