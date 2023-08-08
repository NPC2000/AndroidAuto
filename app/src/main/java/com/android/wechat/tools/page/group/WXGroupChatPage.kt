package com.android.wechat.tools.page.group

import android.util.Log
import com.android.accessibility.ext.acc.click
import com.android.accessibility.ext.acc.findById
import com.android.accessibility.ext.acc.findChildNodes
import com.android.accessibility.ext.default
import com.android.accessibility.ext.task.retryCheckTaskWithLog
import com.android.accessibility.ext.task.retryTaskWithLog
import com.android.wechat.tools.data.WxUserInfo
import com.android.wechat.tools.em.FriendStatus
import com.android.wechat.tools.page.IPage
import com.android.wechat.tools.service.wxAccessibilityService


object WXGroupChatPage : IPage {

    enum class NodeInfo(val nodeText: String, val nodeId: String, val des: String) {
        GroupChatPageTitleNode("群聊(x)", "com.tencent.mm:id/ko4", "新建的群聊页面"),
        GroupChatContentListNode("", "com.tencent.mm:id/b79", "群聊内容列表-RecyclerView"),
        GroupChatMsgNode("", "com.tencent.mm:id/b4b", "群聊消息内容"),
        GroupChatRightTopNode("", "com.tencent.mm:id/eo", "群聊页右上角按钮"),
    }

    override fun pageClassName() = "群聊页"

    override fun pageTitleName() = ""

    override fun isMe(): Boolean {
        //android.widget.TextView → text = 群聊(3) → id = com.tencent.mm:id/ko4
        return wxAccessibilityService?.findById(NodeInfo.GroupChatPageTitleNode.nodeId) != null
    }

    suspend fun inPage(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("判断当前是否在群聊页", timeOutMillis = 20_000) { isMe() }
        }
    }

    /**
     * 检测当前群聊里用户的状态
     */
    suspend fun checkUserStatus(): MutableList<WxUserInfo>? {
        return delayAction(delayMillis = 5000) {
            retryTaskWithLog("开始判断好友状态", timeOutMillis = 10000) {
                //androidx.recyclerview.widget.RecyclerView → text =  → id = com.tencent.mm:id/b79

                //android.widget.TextView → text = 你邀请XXX、XXX加入了群聊 → id = com.tencent.mm:id/b4b → description =  → clickable = true → scrollable = false → editable = false
                //android.widget.TextView → text = 你无法邀请未添加你为好友的用户进去群聊，请先向XXX发送朋友验证申请。对方通过验证后，才能加入群聊。 → id = com.tencent.mm:id/b4b → description =  → clickable = true → scrollable = false → editable = false
                //android.widget.TextView → text = XXX、XXX拒绝加入群聊 → id = com.tencent.mm:id/b4b → description =  → clickable = true → scrollable = false → editable = false
                //android.widget.TextView → text = 由于账号安全原因，XXX、XXX无法加入当前群聊。 → id = com.tencent.mm:id/b4b → description =  → clickable = true → scrollable = false → editable = false

                val list = mutableListOf<WxUserInfo>()
                wxAccessibilityService.findChildNodes(
                    NodeInfo.GroupChatContentListNode.nodeId,
                    NodeInfo.GroupChatMsgNode.nodeId
                ).forEach {
                    val resultText = it.text.default()
                    val result = when {
                        resultText.startsWith("你邀请") -> analyzeNormal(resultText)
                        resultText.startsWith("你无法邀请未添加你为好友的用户进去群聊") -> analyzeDelete(
                            resultText
                        )

                        resultText.startsWith("由于账号安全原因") -> analyzeException(resultText)
                        resultText.endsWith("拒绝加入群聊") -> analyzeBlack(resultText)
                        else -> listOf<WxUserInfo>()
                    }
                    list.addAll(result)
                }
                list
            }
        }
    }

    //你邀请AAA、BBB加入了群聊 → id = com.tencent.mm:id/b4b
    private fun analyzeNormal(text: String): MutableList<WxUserInfo> {
        val result = mutableListOf<WxUserInfo>()
        val start = "你邀请"
        val end = "加入了群聊"
        val users = text.substring(start.length, text.length - end.length)
        val split = users.split("、")
        if (split.isEmpty()) {
            result.add(WxUserInfo(users, "", FriendStatus.NORMAL))
        } else {
            result.addAll(split.map { WxUserInfo(it, "", FriendStatus.NORMAL) }.toMutableList())
        }
        Log.d("检测结果", "analyzeNormal: $result")
        return result
    }

    //你无法邀请未添加你为好友的用户进去群聊，请先向XXX发送朋友验证申请。对方通过验证后，才能加入群聊。 → id = com.tencent.mm:id/b4b
    private fun analyzeDelete(text: String): MutableList<WxUserInfo> {
        val result = mutableListOf<WxUserInfo>()
        val start = "你无法邀请未添加你为好友的用户进去群聊，请先向"
        val end = "发送朋友验证申请。对方通过验证后，才能加入群聊。"
        val users = text.substring(start.length, text.length - end.length)
        val split = users.split("、")
        if (split.isEmpty()) {
            result.add(WxUserInfo(users, "", FriendStatus.DELETE))
        } else {
            result.addAll(split.map { WxUserInfo(it, "", FriendStatus.DELETE) }.toMutableList())
        }
        Log.d("检测结果", "analyzeDelete: $result")
        return result
    }

    //XXX拒绝加入群聊
    private fun analyzeBlack(text: String): MutableList<WxUserInfo> {
        val result = mutableListOf<WxUserInfo>()
        val end = "拒绝加入群聊"
        val users = text.substring(0, text.length - end.length)
        val split = users.split("、")
        if (split.isEmpty()) {
            result.add(WxUserInfo(users, "", FriendStatus.BLACK))
        } else {
            result.addAll(split.map { WxUserInfo(it, "", FriendStatus.BLACK) }.toMutableList())
        }
        Log.d("检测结果", "analyzeBlack: $result")
        return result
    }

    //由于账号安全原因，XXX无法加入当前群聊。
    private fun analyzeException(text: String): MutableList<WxUserInfo> {
        val result = mutableListOf<WxUserInfo>()
        val start = "由于账号安全原因，"
        val end = "无法加入当前群聊。"
        val users = text.substring(start.length, text.length - end.length)
        val split = users.split("、")
        if (split.isEmpty()) {
            result.add(WxUserInfo(users, "", FriendStatus.ACCOUNT_EXCEPTION))
        } else {
            result.addAll(split.map { WxUserInfo(it, "", FriendStatus.ACCOUNT_EXCEPTION) }
                .toMutableList())
        }
        Log.d("检测结果", "analyzeException: $result")
        return result
    }

    suspend fun clickMoreBtn(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("点击【群聊页右上角】按钮", timeOutMillis = 10000) {
                //android.widget.ImageView → text =  → id = com.tencent.mm:id/eo → description = 聊天信息
                wxAccessibilityService?.findById(NodeInfo.GroupChatRightTopNode.nodeId).click()
            }
        }
    }
}