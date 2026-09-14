package com.smsgateway.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smsgateway.app.data.LogEntry

class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPhone: TextView = itemView.findViewById(R.id.tvLogPhone)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvLogStatus)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvLogMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
        private val tvSimSlot: TextView = itemView.findViewById(R.id.tvLogSimSlot)

        fun bind(entry: LogEntry) {
            tvPhone.text = entry.phoneNumber
            tvStatus.text = entry.status
            tvMessage.text = if (entry.error != null) {
                "${entry.message}\nОшибка: ${entry.error}"
            } else {
                entry.message
            }
            tvTime.text = entry.timestamp
            tvSimSlot.text = "SIM ${if (entry.simSlot > 0) entry.simSlot else 1}"

            // Material 3 Semantic Colors
            val (bgColor, textColor) = when (entry.status) {
                "DELIVERED" -> Pair(Color.parseColor("#C4EED0"), Color.parseColor("#07522C"))
                "SENT" -> Pair(Color.parseColor("#D3E3FD"), Color.parseColor("#041E49"))
                "FAILED" -> Pair(Color.parseColor("#F9DEDC"), Color.parseColor("#8C1D18"))
                else -> Pair(Color.parseColor("#FFE7B3"), Color.parseColor("#7C4A00"))
            }

            tvStatus.setTextColor(textColor)
            val bg = tvStatus.background as? GradientDrawable
            bg?.setColor(bgColor)
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem.taskId == newItem.taskId
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
