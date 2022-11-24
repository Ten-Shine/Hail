package com.aistra.hail.ui.home

import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailApi.addTag
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.applyInsetsPadding
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class PagerFragment : MainFragment(), PagerAdapter.OnItemClickListener,
    PagerAdapter.OnItemLongClickListener, MenuProvider {
    private var query: String = String()
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private var multiselect: Boolean
        set(value) {
            if (value) requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner, onBackPressedCallback
            ) else onBackPressedCallback.remove()
            (parentFragment as HomeFragment).multiselect = value
        }
        get() = (parentFragment as HomeFragment).multiselect
    private val selectedList get() = (parentFragment as HomeFragment).selectedList
    private val tabs: TabLayout get() = (parentFragment as HomeFragment).binding.tabs
    private val adapter get() = (parentFragment as HomeFragment).binding.pager.adapter as HomeAdapter
    private val tag: Pair<String, Int> get() = HailData.tags[tabs.selectedTabPosition]
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            activity.appbar.findViewById<Toolbar>(R.id.toolbar)?.run {
                menu.performIdentifierAction(R.id.action_multiselect, 0)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(selectedList).apply {
            onItemClickListener = this@PagerFragment
            onItemLongClickListener = this@PagerFragment
        }
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> activity.fab.run {
                            postDelayed({ if (tag == true) show() }, 1000)
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> activity.run {
                            fab.hide()
                            if (HailData.useBottomSheet) {
                                BottomSheetBehavior.from(bottomSheet).state =
                                    BottomSheetBehavior.STATE_HIDDEN
                            }
                        }
                    }
                }
            })

            this.applyInsetsPadding(start = true, end = true, bottom = true)
        }
        binding.refresh.setOnRefreshListener {
            updateCurrentList()
            binding.refresh.isRefreshing = false
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateCurrentList()
        updateBarTitle()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        tabs.getTabAt(tabs.selectedTabPosition)?.view?.setOnLongClickListener {
            if (isResumed) showChangeTagDialog()
            true
        }
        activity.fab.setOnClickListener {
            if (multiselect) {
                setListFrozen(true, selectedList, false)
                deselect()
            } else {
                setListFrozen(true, pagerAdapter.currentList.filterNot { it.whitelisted })
            }
        }
        activity.fab.setOnLongClickListener {
            setListFrozen(true)
            true
        }
    }

    private fun updateCurrentList() = HailData.checkedList.filter {
        if (query.isEmpty()) tag.second in it.tagId
        else (FuzzySearch.search(it.packageName, query)
                || FuzzySearch.search(it.name.toString(), query)
                || PinyinSearch.searchPinyinAll(it.name.toString(), query))
    }.sortedWith(NameComparator).let {
        binding.empty.isVisible = it.isEmpty()
        pagerAdapter.submitList(it)
        app.setAutoFreezeService()
    }

    private fun updateBarTitle() {
        activity.supportActionBar?.title =
            if (multiselect) getString(R.string.msg_selected, selectedList.size.toString())
            else getString(R.string.app_name)
    }

    private fun updateBottomSheet(info: AppInfo? = null) {
        val bottomSheet = activity.bottomSheet
        val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        val bottomNav: BottomNavigationView? = activity.findViewById(R.id.bottom_nav)

        if (!HailData.useBottomSheet || !multiselect || selectedList.isEmpty() || info == null) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            bottomNav?.visibility = View.VISIBLE
            return
        }

        val actions: Array<String>
        if (selectedList.size == 1) {
            val pkg = info.packageName
            val frozen = AppManager.isAppFrozen(pkg)
            actions = resources.getStringArray(R.array.home_action_entries).filter {
                (it != getString(R.string.action_freeze) || !frozen) && (it != getString(R.string.action_unfreeze) || frozen) && (it != getString(
                    R.string.action_pin
                ) || !info.pinned) && (it != getString(R.string.action_unpin) || info.pinned) && (it != getString(
                    R.string.action_whitelist
                ) || !info.whitelisted) && (it != getString(R.string.action_remove_whitelist) || info.whitelisted) && (it != getString(
                    R.string.action_unfreeze_remove_home
                ) || frozen)
            }.toTypedArray()
        } else {
            actions = resources.getStringArray(R.array.home_action_entries).filter {
                it != getString(R.string.action_launch) && it != getString(R.string.action_deferred_task) && it != getString(
                    R.string.action_pin
                ) && it != getString(R.string.action_unpin) && it != getString(R.string.action_add_pin_shortcut) && it != getString(
                    R.string.action_whitelist
                ) && it != getString(R.string.action_remove_whitelist)
            }.toTypedArray()
        }

        val listView: ListView = bottomSheet.findViewById(R.id.bottom_sheet_list)
        listView.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, actions)

        listView.setOnItemClickListener { _, _, which, _ ->
            if (selectedList.size == 1)
                onOneSelectActionHandle(info, which)
            else
                onMultiSelectActionHandle(which)
        }

        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            bottomNav?.visibility = View.GONE
            if (selectedList.size == 1) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }
    }

    override fun onItemClick(info: AppInfo) {
        if (info.applicationInfo == null) {
            Snackbar.make(activity.fab, R.string.app_not_installed, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_remove_home) { removeCheckedApp(info.packageName) }
                .show()
            return
        }
        if (multiselect) {
            if (info in selectedList) selectedList.remove(info)
            else selectedList.add(info)
            if (HailData.useBottomSheet) {
                if (selectedList.isEmpty())
                    multiselect = false
                updateBottomSheet(info)
            }
            updateCurrentList()
            updateBarTitle()
            return
        }
        launchApp(info.packageName, info.workingMode)
    }

    override fun onItemLongClick(info: AppInfo): Boolean {
        info.applicationInfo ?: return false
        val actions = resources.getStringArray(R.array.home_action_entries)

        if (HailData.useBottomSheet) {
            if (info in selectedList) selectedList.remove(info)
            else selectedList.add(info)
            multiselect = selectedList.isNotEmpty()
            updateCurrentList()
            updateBarTitle()
            updateBottomSheet(info)
            return true
        }

        if (info in selectedList && selectedList.size > 1) {
            MaterialAlertDialogBuilder(activity).setTitle(
                getString(R.string.msg_selected, selectedList.size.toString())
            ).setItems(actions.filter {
                it != getString(R.string.action_launch) && it != getString(R.string.action_deferred_task) && it != getString(
                    R.string.action_pin
                ) && it != getString(R.string.action_unpin) && it != getString(R.string.action_add_pin_shortcut) && it != getString(
                    R.string.action_whitelist
                ) && it != getString(R.string.action_remove_whitelist)
            }.toTypedArray()) { _, which ->
                onMultiSelectActionHandle(which)
            }.setNegativeButton(R.string.action_deselect) { _, _ ->
                deselect()
            }.setNeutralButton(R.string.action_select_all) { _, _ ->
                selectedList.addAll(pagerAdapter.currentList.filterNot { it in selectedList })
                updateCurrentList()
                updateBarTitle()
            }.show()
            return true
        }

        val pkg = info.packageName
        val frozen = AppManager.isAppFrozen(pkg)
        MaterialAlertDialogBuilder(activity).setTitle(info.name).setItems(
            actions.filter {
                (it != getString(R.string.action_freeze) || !frozen) && (it != getString(R.string.action_unfreeze) || frozen) && (it != getString(
                    R.string.action_pin
                ) || !info.pinned) && (it != getString(R.string.action_unpin) || info.pinned) && (it != getString(
                    R.string.action_whitelist
                ) || !info.whitelisted) && (it != getString(R.string.action_remove_whitelist) || info.whitelisted) && (it != getString(
                    R.string.action_unfreeze_remove_home
                ) || frozen)
            }.toTypedArray()
        ) { _, which ->
            onOneSelectActionHandle(info, which)
        }.apply {
            if (multiselect) {
                setNeutralButton(R.string.action_select_all) { _, _ ->
                    selectedList.addAll(pagerAdapter.currentList.filterNot { it in selectedList })
                    updateCurrentList()
                    updateBarTitle()
                }.setNegativeButton(R.string.action_deselect) { _, _ ->
                    deselect()
                }
            } else {
                setNeutralButton(R.string.action_details) { _, _ ->
                    HUI.startActivity(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg)
                    )
                }.setNegativeButton(android.R.string.cancel, null)
            }
        }.show()
        return true
    }

    private fun deselect(update: Boolean = true) {
        selectedList.clear()
        if (!update) return
        if (HailData.useBottomSheet)
            multiselect = false
        updateCurrentList()
        updateBarTitle()
        updateBottomSheet()
    }

    private fun onOneSelectActionHandle(info: AppInfo, which: Int) {
        val pkg = info.packageName
        val frozen = AppManager.isAppFrozen(pkg)
        val action = getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)
        when (which) {
            0 -> launchApp(pkg, info.workingMode)
            1 -> setListFrozen(!frozen, listOf(info))
            2 -> {
                val values = resources.getIntArray(R.array.deferred_task_values)
                val entries = arrayOfNulls<String>(values.size)
                values.forEachIndexed { i, it ->
                    entries[i] =
                        resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
                }
                MaterialAlertDialogBuilder(activity).setTitle(R.string.action_deferred_task)
                    .setItems(entries) { _, i ->
                        HWork.setDeferredFrozen(pkg, !frozen, values[i].toLong())
                        Snackbar.make(
                            activity.fab, resources.getQuantityString(
                                R.plurals.msg_deferred_task,
                                values[i],
                                values[i],
                                action,
                                info.name
                            ), Snackbar.LENGTH_INDEFINITE
                        ).setAction(R.string.action_undo) { HWork.cancelWork(pkg) }.show()
                        if (multiselect && info in selectedList) deselect()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }

            3 -> {
                info.pinned = !info.pinned
                HailData.saveApps()
                updateCurrentList()
            }

            4 -> {
                info.whitelisted = !info.whitelisted
                HailData.saveApps()
                info.selected =
                    !info.selected // This is a workaround to make contents not same
                updateCurrentList()
            }

            5 -> {
                val defaultIndex = resources.getStringArray(R.array.working_mode_values)
                    .indexOf(HailData.workingMode)
                val defaultString = resources.getString(
                    R.string.mode_default_summary,
                    resources.getStringArray(R.array.working_mode_entries)[defaultIndex]
                )

                var selection =
                    resources.getStringArray(R.array.working_mode_values).indexOf(info.workingMode)
                MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.working_mode)
                    .setSingleChoiceItems(
                        resources.getStringArray(R.array.working_mode_entries)
                            .mapIndexed { index, s ->
                                if (index == 0) defaultString
                                else s
                            }.toTypedArray(), selection
                    ) { _, index ->
                        selection = index
                    }.setPositiveButton(android.R.string.ok) { dialog, _ ->
                        info.workingMode = resources.getStringArray(R.array.working_mode_values)[selection]
                        HailData.saveApps()
                        if (multiselect && info in selectedList) deselect()
                        dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }

            6 -> showSetTagDialog(listOf(info))

            7 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                    HShortcuts.addPinShortcut(
                        info,
                        pkg,
                        info.name,
                        HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                            .addTag(HailData.tags[index].first)
                    )
                    if (multiselect && info in selectedList) deselect()
                }.setPositiveButton(R.string.action_skip) { _, _ ->
                    HShortcuts.addPinShortcut(
                        info,
                        pkg,
                        info.name,
                        HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                    )
                }.setNegativeButton(android.R.string.cancel, null).show()

            8 -> exportToClipboard(listOf(info))
            9 -> removeCheckedApp(pkg)
            10 -> {
                setListFrozen(false, listOf(info), false)
                if (!AppManager.isAppFrozen(pkg)) removeCheckedApp(pkg)
            }
        }
        if (multiselect && info in selectedList && which != 2 && which != 5 && which != 6)
            deselect()
    }

    private fun onMultiSelectActionHandle(which: Int) {
        when (which) {
            0 -> {
                setListFrozen(true, selectedList, false)
                deselect()
            }

            1 -> {
                setListFrozen(false, selectedList, false)
                deselect()
            }

            2 -> {
                val defaultIndex = resources.getStringArray(R.array.working_mode_values)
                    .indexOf(HailData.workingMode)
                val defaultString = resources.getString(
                    R.string.mode_default_summary,
                    resources.getStringArray(R.array.working_mode_entries)[defaultIndex]
                )

                var selection = 0
                MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.working_mode)
                    .setSingleChoiceItems(
                        resources.getStringArray(R.array.working_mode_entries)
                            .mapIndexed { index, s ->
                                if (index == 0) defaultString
                                else s
                            }.toTypedArray(), 0
                    ) { _, index ->
                        selection = index
                    }.setPositiveButton(android.R.string.ok) { dialog, _ ->
                        for (app in selectedList) {
                            app.workingMode =
                                resources.getStringArray(R.array.working_mode_values)[selection]
                        }
                        deselect()
                        dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }

            3 -> showSetTagDialog(selectedList)

            4 -> {
                exportToClipboard(selectedList)
                deselect()
            }

            5 -> {
                selectedList.forEach { removeCheckedApp(it.packageName, false) }
                HailData.saveApps()
                deselect()
            }

            6 -> {
                setListFrozen(false, selectedList, false)
                selectedList.forEach {
                    if (!AppManager.isAppFrozen(it.packageName))
                        removeCheckedApp(it.packageName, false)
                }
                HailData.saveApps()
                deselect()
            }
        }
    }

    private fun launchApp(packageName: String, workingMode: String) {
        if (AppManager.isAppFrozen(packageName) &&
            AppManager.setAppFrozen(packageName, false, workingMode)
        ) {
            updateCurrentList()
        }
        app.packageManager.getLaunchIntentForPackage(packageName)?.let {
            HShortcuts.addDynamicShortcut(packageName)
            startActivity(it)
        } ?: HUI.showToast(R.string.activity_not_found)
    }

    private fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, updateList: Boolean = true
    ) {
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val filtered = list.filter { AppManager.isAppFrozen(it.packageName) != frozen }
        when (val result = AppManager.setListFrozen(frozen, *filtered.toTypedArray())) {
            null -> HUI.showToast(R.string.permission_denied)
            else -> {
                if (updateList) updateCurrentList()
                HUI.showToast(
                    if (frozen) R.string.msg_freeze else R.string.msg_unfreeze,
                    result
                )
            }
        }
    }

    private fun showSetTagDialog(appList: List<AppInfo>) {
        val choices = BooleanArray(HailData.tags.size,
            if (appList.all { it.tagId == appList[0].tagId }) { index ->
                HailData.tags[index].second in appList[0].tagId
            } else { _ -> false }
        )
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set)
            .setMultiChoiceItems(
                HailData.tags.map { it.first }.toTypedArray(), choices
            ) { _, index, isChecked ->
                choices[index] = isChecked
            }.setNeutralButton(R.string.action_tag_add) { _, _ ->
                showAddTagDialog(appList)
            }.setPositiveButton(android.R.string.ok) { _, _ ->
                val newTagId = choices.toMutableList().mapIndexedNotNull { index, checked ->
                    if (checked) HailData.tags[index].second else null
                }
                if (newTagId.isEmpty()) appList.forEach {
                    HailData.removeCheckedApp(it.packageName, false)
                } else appList.forEach {
                    if (it.tagId != newTagId) {
                        it.tagId.clear()
                        it.tagId.addAll(newTagId)
                    }
                }
                HailData.saveApps()
                if (multiselect && appList == selectedList) deselect()
                else updateCurrentList()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showAddTagDialog(list: List<AppInfo>? = null) {
        val input = DialogInputBinding.inflate(layoutInflater, FrameLayout(activity), true)
        input.inputLayout.setHint(R.string.action_tag_add)
        MaterialAlertDialogBuilder(activity).setView(input.root.parent as View)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val tagName = input.editText.text.toString()
                val tagId = tagName.hashCode()
                if (HailData.tags.any { it.first == tagName || it.second == tagId }) {
                    list?.let { showSetTagDialog(it) }
                    return@setPositiveButton
                }
                HailData.tags.add(tagName to tagId)
                adapter.notifyItemInserted(adapter.itemCount - 1)
                if (query.isEmpty() && tabs.tabCount == 2) tabs.isVisible = true
                HailData.saveApps()
                HailData.saveTags()
                list?.let { showSetTagDialog(it) }
            }.setNegativeButton(android.R.string.cancel) { _, _ ->
                list?.let { showSetTagDialog(it) }
            }.show()
    }

    private fun showChangeTagDialog() {
        val input = DialogInputBinding.inflate(layoutInflater, FrameLayout(activity), true)
        input.inputLayout.setHint(R.string.action_tag_set)
        input.editText.setText(tag.first)
        MaterialAlertDialogBuilder(activity).setView(input.root.parent as View)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val tagName = input.editText.text.toString()
                val tagId = tagName.hashCode()
                if (HailData.tags.any { it.first == tagName || it.second == tagId }) return@setPositiveButton
                val position = tabs.selectedTabPosition
                val originTagId = HailData.tags[position].second
                val defaultTab = position == 0
                HailData.tags.run {
                    removeAt(position)
                    add(position, tagName to if (defaultTab) 0 else tagId)
                }
                if (!defaultTab) {
                    pagerAdapter.currentList.filter { it.tagId.contains(originTagId) }.forEach {
                        it.tagId[it.tagId.indexOf(originTagId)] = tagId
                    }
                }
                adapter.notifyItemChanged(position)
                HailData.saveApps()
                HailData.saveTags()
            }.apply {
                val position = tabs.selectedTabPosition
                if (position == 0) return@apply
                setNeutralButton(R.string.action_tag_remove) { _, _ ->
                    pagerAdapter.currentList.forEach {
                        it.tagId.remove(HailData.tags[position].second)
                        if (it.tagId.isEmpty()) HailData.checkedList.remove(it)
                    }
                    tabs.selectTab(tabs.getTabAt(0))
                    HailData.tags.removeAt(position)
                    adapter.notifyItemRemoved(position)
                    if (tabs.tabCount == 1) tabs.isVisible = false
                    HailData.saveApps()
                    HailData.saveTags()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun exportToClipboard(list: List<AppInfo>) {
        if (list.isEmpty()) return
        HUI.copyText(if (list.size > 1) JSONArray().run {
            list.forEach { put(it.packageName) }
            toString()
        } else list[0].packageName)
        HUI.showToast(
            R.string.msg_exported, if (list.size > 1) list.size.toString() else list[0].name
        )
    }

    private fun importFromClipboard() = runCatching {
        val str = HUI.pasteText() ?: throw IllegalArgumentException()
        val json = if (str.contains('[')) JSONArray(
            str.substring(
                str.indexOf('[')..str.indexOf(']', str.indexOf('['))
            )
        )
        else JSONArray().put(str)
        var i = 0
        for (index in 0 until json.length()) {
            val pkg = json.getString(index)
            if (HPackages.getApplicationInfoOrNull(pkg) != null && !HailData.isChecked(pkg)) {
                HailData.addCheckedApp(pkg, false, mutableListOf(tag.second))
                i++
            }
        }
        if (i > 0) {
            HailData.saveApps()
            updateCurrentList()
        }
        HUI.showToast(getString(R.string.msg_imported, i.toString()))
    }

    private suspend fun importFrozenApp() = withContext(Dispatchers.IO) {
        HPackages.getInstalledApplications().map { it.packageName }
            .filter { AppManager.isAppFrozen(it) && !HailData.isChecked(it) }
            .onEach { HailData.addCheckedApp(it, false, mutableListOf(tag.second)) }.size
    }

    private fun removeCheckedApp(packageName: String, saveApps: Boolean = true) {
        HailData.removeCheckedApp(packageName, saveApps)
        if (saveApps) updateCurrentList()
    }

    private fun MenuItem.updateIcon() = icon?.setTint(
        MaterialColors.getColor(
            activity.findViewById(R.id.toolbar),
            if (multiselect) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
        )
    )

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_multiselect -> {
                multiselect = !multiselect
                item.updateIcon()
                if (multiselect) {
                    updateBarTitle()
                    HUI.showToast(R.string.tap_to_select)
                } else deselect()
            }

            R.id.action_freeze_current -> setListFrozen(true,
                pagerAdapter.currentList.filterNot { it.whitelisted })

            R.id.action_unfreeze_current -> setListFrozen(false, pagerAdapter.currentList)
            R.id.action_freeze_all -> setListFrozen(true)
            R.id.action_unfreeze_all -> setListFrozen(false)
            R.id.action_freeze_non_whitelisted -> setListFrozen(true,
                HailData.checkedList.filterNot { it.whitelisted })

            R.id.action_import_clipboard -> importFromClipboard()
            R.id.action_import_frozen -> lifecycleScope.launch {
                val size = importFrozenApp()
                if (size > 0) {
                    HailData.saveApps()
                    updateCurrentList()
                }
                HUI.showToast(getString(R.string.msg_imported, size.toString()))
            }

            R.id.action_export_current -> exportToClipboard(pagerAdapter.currentList)
            R.id.action_export_all -> exportToClipboard(HailData.checkedList)
            R.id.action_select_apps -> findNavController().navigate(R.id.nav_apps)
            R.id.action_settings -> findNavController().navigate(R.id.nav_settings)
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_home, menu)
        (menu.findItem(R.id.action_search).actionView as SearchView).setOnQueryTextListener(object :
            SearchView.OnQueryTextListener {
            private var inited = false
            override fun onQueryTextChange(newText: String): Boolean {
                if (inited) {
                    query = newText
                    tabs.isVisible = query.isEmpty() && tabs.tabCount > 1
                    updateCurrentList()
                } else inited = true
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean = true
        })
        if (HailData.useBottomSheet) {
            menu.findItem(R.id.action_multiselect).setVisible(false)
        } else {
            menu.findItem(R.id.action_multiselect).updateIcon()
        }
    }

    override fun onDestroyView() {
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}