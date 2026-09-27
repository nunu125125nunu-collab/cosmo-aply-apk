package com.example.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.ItemDownloadedPackageBinding
import com.example.model.DownloadedPackage

class LibraryAdapter(
    private val onOpenClick: (DownloadedPackage) -> Unit,
    private val onInstallClick: (DownloadedPackage) -> Unit,
    private val onDeleteClick: (DownloadedPackage) -> Unit
) : ListAdapter<DownloadedPackage, LibraryAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadedPackageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDownloadedPackageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadedPackage) {
            binding.tvPackageName.text = item.appTitle
            binding.tvPackageSub.text = item.subInfo
            binding.tvTypeBadge.text = item.typeLabel

            if (item.iconDrawable != null) {
                binding.ivPackageIcon.setImageDrawable(item.iconDrawable)
            } else {
                binding.ivPackageIcon.setImageResource(R.drawable.ic_android_package)
            }

            if (item.isInstalled) {
                binding.tvStatusBadge.text = "Installed in Cosmo Game Store"
                binding.tvStatusBadge.setTextColor(binding.root.context.getColor(R.color.accent_green))
                binding.btnOpenApp.isVisible = true
                binding.btnOpenApp.setOnClickListener { onOpenClick(item) }

                binding.btnInstallPackage.text = "Reinstall"
                binding.btnInstallPackage.isVisible = (item.file != null && item.file.exists())
            } else {
                binding.tvStatusBadge.text = "Downloaded • Ready to Install"
                binding.tvStatusBadge.setTextColor(binding.root.context.getColor(R.color.accent_amber))
                binding.btnOpenApp.isVisible = false

                binding.btnInstallPackage.text = "Install"
                binding.btnInstallPackage.isVisible = (item.file != null && item.file.exists())
            }

            binding.btnInstallPackage.setOnClickListener {
                onInstallClick(item)
            }

            binding.btnDeletePackage.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DownloadedPackage>() {
        override fun areItemsTheSame(oldItem: DownloadedPackage, newItem: DownloadedPackage): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: DownloadedPackage, newItem: DownloadedPackage): Boolean {
            return oldItem == newItem
        }
    }
}
