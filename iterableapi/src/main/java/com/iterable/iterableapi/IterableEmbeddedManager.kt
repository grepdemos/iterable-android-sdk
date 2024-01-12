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
    private var localPlacementMessagesMap = mutableMapOf<Long, MutableList<IterableEmbeddedMessage>>()
    private var messageIdsMap = mutableMapOf<Long, MutableList<IterableEmbeddedMessage>>()

    private var placementIds = mutableListOf<Long>()
    private var messageIds = mutableListOf<String>()

    private var updateHandleListeners = mutableListOf<IterableEmbeddedUpdateHandler>()
    private var iterableApi: IterableApi
    private var context: Context

    private var embeddedSessionManager = EmbeddedSessionManager()

    private var activityMonitor: IterableActivityMonitor? = null
    //private var currentMessageIds: Array<String>  = arrayOf()

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
    fun getMessages(placementId: Long): List<IterableEmbeddedMessage>? {
        return localPlacementMessagesMap[placementId]
    }

    fun reset() {
        localPlacementMessagesMap = mutableMapOf()
        messageIds = mutableListOf<String>()
    }

    fun getPlacementIds(): List<Long> {
        return placementIds
    }

    //Network call to get the embedded messages
    fun syncMessages() {
        IterableLogger.v(TAG, "Syncing messages...")

        IterableApi.sharedInstance.getEmbeddedMessages(messageIds, null, SuccessHandler { data ->
            IterableLogger.v(TAG, "Got response from network call to get embedded messages")
            try {
                val previousPlacementIds = getPlacementIds()
                val currentPlacementIds: MutableList<Long> = mutableListOf()

                val placementsArray = data.optJSONArray(IterableConstants.ITERABLE_EMBEDDED_MESSAGE_PLACEMENTS)
                if (placementsArray != null) {
                    //if there are no placements in the payload
                    //reset the local message storage and trigger a UI update
                    if(placementsArray.length() == 0) {
                        reset()
                        if(previousPlacementIds.isNotEmpty()) {
                            updateHandleListeners.forEach {
                                IterableLogger.d(TAG, "Calling updateHandler")
                                it.onMessagesUpdated()
                            }
                        }
                    } else {
                        for (i in 0 until placementsArray.length()) {
                            val placementJson = placementsArray.optJSONObject(i)
                            val placement = IterableEmbeddedPlacement.fromJSONObject(placementJson)
                            val placementId = placement.placementId
                            val messages = placement.messages

                            currentPlacementIds.add(placementId)

                            messages.forEach {
                                if (!messageIds.contains(it.metadata.messageId)) {
                                    messageIds.add(it.metadata.messageId)
                                }
                            }

                            updateLocalMessageMap(placementId, messages)
                        }
                    }
                }

                // compare previous placements to the current placement payload
                val removedPlacementIds = previousPlacementIds.subtract(currentPlacementIds.toSet())

                //if there are placements removed, update the local storage and trigger UI update
                if(removedPlacementIds.isNotEmpty()) {
                    removedPlacementIds.forEach {
                        localPlacementMessagesMap.remove(it)
                    }

                    updateHandleListeners.forEach {
                        IterableLogger.d(TAG, "Calling updateHandler")
                        it.onMessagesUpdated()
                    }
                }

                //store placements from payload for next comparison
                placementIds = currentPlacementIds

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

    private fun updateLocalMessageMap(
        placementId: Long,
        remoteMessageList: List<IterableEmbeddedMessage>
    ) {
        IterableLogger.printInfo()
        var localMessagesChanged = false

        var localMessages: MutableList<IterableEmbeddedMessage>? = getMessages(placementId)?.toMutableList()

        // Get local messages in a mutable list
        val localMessageMap = mutableMapOf<String, IterableEmbeddedMessage>()
        localMessages?.forEach {
            localMessageMap[it.metadata.messageId] = it
        }

        // Compare the remote list to local list
        // if there are new messages, trigger a message update in UI and send out received events
        remoteMessageList.forEach { embeddedMessage ->
            val position = remoteMessageList.indexOf(embeddedMessage)

            if(embeddedMessage.elements != null) {
                if(localMessages == null) {
                    localMessages = mutableListOf(embeddedMessage)
                } else {
                    localMessages?.add(position, embeddedMessage)
                }
                localMessagesChanged = true
                IterableApi.getInstance().trackEmbeddedMessageReceived(embeddedMessage)
                if (!messageIds.contains(embeddedMessage.metadata.messageId)) {
                    messageIds.add(embeddedMessage.metadata.messageId)
                }
            }
        }

//        val sharedPref = IterableApi.sharedInstance.mainActivityContext.getSharedPreferences(IterableConstants.SHARED_PREFS_FILE, Context.MODE_PRIVATE)
//        val editor = sharedPref.edit()
//        editor.putStringSet(IterableConstants.SHARED_PREFS_CURRENT_EMBEDDED_MSGS, currentMessageIds.toSet())
//        editor.apply()

        // Compare the local list to remote list
        // if there are messages to remove, trigger a message update in UI
        val remoteMessageMap = mutableMapOf<String, IterableEmbeddedMessage>()
        remoteMessageList.forEach {
            remoteMessageMap[it.metadata.messageId] = it
        }

        val iterator = localMessages?.iterator()
        while (iterator?.hasNext() == true) {
            val embeddedMessage = iterator.next()
            if (!remoteMessageMap.containsKey(embeddedMessage.metadata.messageId)) {
                localMessagesChanged = true
                iterator.remove()
                localMessages?.remove(embeddedMessage)
            }
        }

        // update local message map for placement with remote message list
        IterableLogger.d(TAG, "remote message list: $remoteMessageList")
        if(localMessages != null) {
            localPlacementMessagesMap[placementId] = localMessages!!
        }

        //if local messages changed, trigger a message update in UI
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
