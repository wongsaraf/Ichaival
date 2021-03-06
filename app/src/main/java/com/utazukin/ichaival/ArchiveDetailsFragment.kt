/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2020 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival


import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ARCHIVE_ID = "arcid"

class ArchiveDetailsFragment : Fragment(), TabRemovedListener, TabsClearedListener, TabAddedListener {
    private var archiveId: String? = null
    private lateinit var tagLayout: LinearLayout
    private lateinit var bookmarkButton: Button
    private var thumbLoadJob: Job? = null
    private var tagListener: TagInteractionListener? = null
    private val isLocalSearch by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.getBoolean(getString(R.string.local_search_key), false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            archiveId = it.getString(ARCHIVE_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_archive_details, container, false)
        tagLayout = view.findViewById(R.id.tag_layout)

        lifecycleScope.launch {
            val archive = withContext(Dispatchers.Default) { DatabaseReader.getArchive(archiveId!!) }
            setUpDetailView(view, archive)
        }
        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        ReaderTabHolder.registerRemoveListener(this)
        ReaderTabHolder.registerClearListener(this)
        ReaderTabHolder.registerAddListener(this)
        tagListener = context as TagInteractionListener?
    }

    override fun onDetach() {
        super.onDetach()
        ReaderTabHolder.unregisterRemoveListener(this)
        ReaderTabHolder.unregisterClearListener(this)
        ReaderTabHolder.unregisterAddListener(this)
    }

    override fun onTabAdded(id: String) {
        if (id == archiveId)
            lifecycleScope.launch { bookmarkButton.text = getString(R.string.unbookmark) }
    }

    override fun onTabRemoved(id: String) {
        if (id == archiveId) {
            lifecycleScope.launch { bookmarkButton.text = getString(R.string.bookmark) }
        }
    }

    override fun onTabsCleared() {
        bookmarkButton.text = getString(R.string.bookmark)
    }

    override fun onDestroy() {
        thumbLoadJob?.cancel()
        super.onDestroy()
    }

    private fun getSearchTag(tag: String, namespace: String) : String {
        return when {
            namespace == "Other:" -> "\"$tag\""
            isLocalSearch -> "$namespace\"$tag\""
            else -> "\"$namespace$tag\"$"
        }
    }

    private fun setUpTags(archive: Archive) {
        for (pair in archive.tags) {
            if (pair.value.isEmpty())
                continue

            val namespace = if (pair.key == "global") "Other:" else "${pair.key}:"
            val namespaceLayout = FlexboxLayout(context)
            namespaceLayout.flexWrap = FlexWrap.WRAP
            namespaceLayout.flexDirection = FlexDirection.ROW
            tagLayout.addView(
                namespaceLayout,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            val namespaceView = createTagView(namespace)
            namespaceLayout.addView(namespaceView)

            val isSource = namespace == "source:"
            for (tag in pair.value) {
                val tagView = createTagView(tag)
                namespaceLayout.addView(tagView)

                if (!isSource) {
                    val searchTag = getSearchTag(tag, namespace)
                    tagView.setOnClickListener { tagListener?.onTagInteraction(searchTag) }
                } else {
                    tagView.linksClickable = true
                    Linkify.addLinks(tagView, Linkify.WEB_URLS)
                    tagView.movementMethod = LinkMovementMethod.getInstance()
                }
            }
        }
    }

    private fun createTagView(tag: String) : TextView {
        val tagView = TextView(context)
        tagView.text = tag
        tagView.background = ContextCompat.getDrawable(requireContext(), R.drawable.tag_background)
        tagView.setTextColor(Color.WHITE)
        val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layoutParams.setMargins(10, 10, 10, 10)
        tagView.layoutParams = layoutParams
        return tagView
    }

    private fun setUpDetailView(view: View, archive: Archive?) {
        bookmarkButton = view.findViewById(R.id.bookmark_button)
        with(bookmarkButton) {
            setOnClickListener {
                lifecycleScope.launch {
                    archiveId?.let {
                        if (ReaderTabHolder.isTabbed(it)) {
                            ReaderTabHolder.removeTab(it)
                            text = getString(R.string.bookmark)
                        } else {
                            ReaderTabHolder.addTab(it, 0)
                            text = getString(R.string.unbookmark)
                        }
                    }
                }
            }
            text = getString(if (ReaderTabHolder.isTabbed(archive)) R.string.unbookmark else R.string.bookmark)
        }

        val readButton: Button = view.findViewById(R.id.read_button)
        readButton.setOnClickListener { (activity as ArchiveDetails).startReaderActivityForResult() }

        if (archive != null) {
            setUpTags(archive)

            val titleView: TextView = view.findViewById(R.id.title)
            titleView.text = archive.title

            thumbLoadJob = lifecycleScope.launch(Dispatchers.Main) {
                val thumbView: ImageView = view.findViewById(R.id.cover)
                val thumb =
                    withContext(Dispatchers.Default) { DatabaseReader.getArchiveImage(archive, requireContext().filesDir) }
                val request = Glide.with(thumbView).load(thumb).dontTransform()
                request.into(thumbView)

                //Replace the thumbnail with the full size image.
                val image = withContext(Dispatchers.Default) { archive.getPageImage(0) }
                Glide.with(thumbView).load(image).dontTransform().thumbnail(request).into(thumbView)
            }
        }
    }

    interface TagInteractionListener {
        fun onTagInteraction(tag: String)
    }

    companion object {
        @JvmStatic
        fun createInstance(id: String) =
            ArchiveDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARCHIVE_ID, id)
                }
            }
    }
}
