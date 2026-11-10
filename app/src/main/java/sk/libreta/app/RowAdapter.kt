package sk.libreta.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
    val title: TextView = view.findViewById(R.id.title)
    val subtitle: TextView = view.findViewById(R.id.subtitle)
    val value: TextView = view.findViewById(R.id.value)
    val action: MaterialButton = view.findViewById(R.id.action)
    val action2: MaterialButton = view.findViewById(R.id.action2)
}

/** One adapter for every list in the app: each screen only says how to fill a row. */
class RowAdapter<T>(private val bind: (RowHolder, T) -> Unit) : RecyclerView.Adapter<RowHolder>() {

    var items: List<T> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.action.visibility = View.GONE
        holder.action.setOnClickListener(null)
        holder.action2.visibility = View.GONE
        holder.action2.setOnClickListener(null)
        holder.itemView.setOnClickListener(null)
        holder.itemView.alpha = 1f
        bind(holder, items[position])
        holder.subtitle.visibility = if (holder.subtitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

fun RecyclerView.setup(adapter: RecyclerView.Adapter<*>) {
    layoutManager = LinearLayoutManager(context)
    addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
    this.adapter = adapter
}
