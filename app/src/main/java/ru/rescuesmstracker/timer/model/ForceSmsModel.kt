/**
 * Copyright (C) 2020 Safety Tracker
 *
 * This file is part of Open SMS Locator
 *
 * Open SMS Locator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * Open SMS Locator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Open SMS Locator. If not, see <https://www.gnu.org/licenses/>.
 */

package ru.rescuesmstracker.timer.model

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import io.realm.Realm
import ru.rescuesmstracker.LocationProvider
import ru.rescuesmstracker.RSTSystem
import ru.rescuesmstracker.data.Contact
import ru.rescuesmstracker.data.Sms
import ru.rescuesmstracker.onboarding.FormatUtils
import java.util.*

object ForceSmsModel : BaseSmsModel() {
    interface SmsListener {
        fun onStatusChanged(status: Sms.Status, totalSmsIdsList: List<String>)
        fun onAllSmsSent()
    }

    /**
     * Just external interface to hide the difference between to types of tasks
     */
    interface Task {
        fun onDestroy()
    }

    private val totalSmsIdsListKey = "ru.rescuesmstracker.timer.model.ForceSmsModel.totalSmsIdsListKey"
    private val tasks: MutableMap<String, ForceSmsTaskObservingTask> = mutableMapOf()

    fun forceSendLocation(context: Context, smsType: Sms.Type, contacts: List<Contact>, listener: SmsListener?)
            : Task {
        val task = ForceSendSmsExecutableTask(listener).execute(context, smsType, contacts)
        tasks.put(task.id, task)
        return task
    }

    /**
     * This method should be used by model consumer to subscribe on pending sms status changes.
     * It will try to recover te data from persistent storages
     */
    fun subscribe(context: Context, taskId: String, listener: SmsListener) {
        val task = tasks[taskId]
        if (task == null) {
            val totalSmsIds = getTotalSmsIdsList(context)
            if (totalSmsIds.isEmpty()) {
                // no active task with such id and no sms. All sms have been sent
                listener.onAllSmsSent()
            } else {
                val totalSmsIdsList = totalSmsIds.toMutableList()
                listener.onStatusChanged(Sms.Status.SENDING, totalSmsIdsList)
                val totalSms = Realm.getDefaultInstance().where(Sms::class.java)
                        .`in`("id", totalSmsIds.toTypedArray())
                        .findAll()
                val pendingSmsList = totalSms
                        .filter { it.getStatus() == Sms.Status.SENDING }
                        .map { it.id }
                        .toMutableList()
                if (pendingSmsList.isEmpty()) {
                    // if there is no pending sms we just need to notify listener and cleanup prefs
                    persistTotalSmsIdsList(context, null)
                    listener.onAllSmsSent()
                } else {
                    // we have some pending sms. So just init observing task and wait for notifyTask() method call
                    tasks.put(taskId, ForceSmsTaskObservingTask(listener, totalSmsIdsList, pendingSmsList))
                }
            }
        } else {
            task.listener = listener
        }
    }

    /**
     * Method is used by [ru.rescuesmstracker.timer.SmsDeliveredReceiver] to notify that another sms
     * has been sent.
     */
    fun notifyTask(context: Context, taskId: String?, sms: Sms) {
        if (taskId != null) {
            val task = tasks[taskId]
            if (task != null && task.notifySmsStatus(sms)) {
                tasks.remove(taskId)
            }
            // remove total sms even if there is no task to notify now. When listener will subscribe
            // it will receive the result
            persistTotalSmsIdsList(context, null)
        }
    }

    private fun persistTotalSmsIdsList(context: Context, totalSmsIdsList: Collection<String>?) {
        if (totalSmsIdsList == null || totalSmsIdsList.isEmpty()) {
            prefs(context).edit()
                    .remove(totalSmsIdsListKey)
                    .apply()
        } else {
            prefs(context).edit()
                    .putStringSet(totalSmsIdsListKey, totalSmsIdsList.toSet())
                    .apply()
        }
    }

    private fun getTotalSmsIdsList(context: Context): Collection<String>
            = prefs(context).getStringSet(totalSmsIdsListKey, emptySet())

    private fun prefs(context: Context): SharedPreferences = context
            .getSharedPreferences("force_sms_model", Context.MODE_PRIVATE)

    private open class ForceSmsTaskObservingTask(var listener: SmsListener?,
                                                 val totalSmsIdsList: MutableList<String>,
                                                 val pendingSmsIdsList: MutableList<String>) : Task {
        fun notifySmsStatus(sms: Sms): Boolean {
            pendingSmsIdsList.remove(sms.id)
            listener?.onStatusChanged(sms.getStatus(), totalSmsIdsList)
            val areAllSmsSent = pendingSmsIdsList.isEmpty()
            if (areAllSmsSent) {
                listener?.onAllSmsSent()
            }
            return areAllSmsSent
        }

        override fun onDestroy() {
            listener = null
        }
    }

    private class ForceSendSmsExecutableTask(listener: SmsListener?)
        : ForceSmsTaskObservingTask(listener, mutableListOf(), mutableListOf()) {
        val id = UUID.randomUUID().toString()

        fun execute(context: Context, smsType: Sms.Type, contacts: List<Contact>): ForceSendSmsExecutableTask {
            listener?.onStatusChanged(Sms.Status.SENDING, totalSmsIdsList)
            LocationProvider.currentLocation(context, object : LocationProvider.LocationCallback {
                override fun onReceivedLocation(location: Location, isLastKnown: Boolean) {
                    contacts.forEach { contact ->
                        val realm = Realm.getDefaultInstance()
                        realm.beginTransaction()
                        val sms = Realm.getDefaultInstance()
                                .copyToRealm(Sms(contact, RSTSystem.currentTimeMillis(),
                                        FormatUtils(context).formatLocationSms(location, isLastKnown), smsType))
                        realm.commitTransaction()
                        totalSmsIdsList.add(sms.id)
                        pendingSmsIdsList.add(sms.id)
                        performLocationSmsSending(context, sms, id)
                    }
                    persistTotalSmsIdsList(context, totalSmsIdsList)
                }

                override fun onFailedToGetLocation() {
                }
            })
            return this
        }
    }
}