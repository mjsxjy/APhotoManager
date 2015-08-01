/*
 * Copyright (c) 2015 by k3b.
 *
 * This file is part of AndroFotoFinder.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */
 
package de.k3b.android.androFotoFinder.gallery.cursor;

import android.app.Activity;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.app.Fragment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

// import com.squareup.leakcanary.RefWatcher;

import java.util.List;

import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.directory.DirectoryGui;
import de.k3b.android.androFotoFinder.directory.DirectoryPickerFragment;
import de.k3b.android.androFotoFinder.queries.FotoViewerParameter;
import de.k3b.android.androFotoFinder.queries.QueryParameterParcelable;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.androFotoFinder.OnGalleryInteractionListener;
import de.k3b.android.androFotoFinder.queries.Queryable;
import de.k3b.database.SelectedItems;
import de.k3b.io.Directory;

/**
 * A {@link Fragment} to show ImageGallery content based on ContentProvider-Cursor.
 * Activities that contain this fragment must implement the
 * {@link OnGalleryInteractionListener} interface
 * to handle interaction events.
 * Use the {@link GalleryCursorFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class GalleryCursorFragment extends Fragment  implements Queryable, DirectoryGui {
    private static final String INSTANCE_STATE_LAST_VISIBLE_POSITION = "lastVisiblePosition";
    private static final String INSTANCE_STATE_SELECTED_ITEM_IDS = "selectedItems";
    private static final String INSTANCE_STATE_OLD_TITLE = "oldTitle";

    private HorizontalScrollView parentPathBarScroller;
    private LinearLayout parentPathBar;

    private HorizontalScrollView childPathBarScroller;
    private LinearLayout childPathBar;

    // for debugging
    private static int id = 1;
    private final String debugPrefix;

    private GridView galleryView;
    private GalleryCursorAdapter galleryAdapter = null;

    private OnGalleryInteractionListener mGalleryListener;
    private QueryParameterParcelable mGalleryContentQuery;

    private DirectoryPickerFragment.OnDirectoryInteractionListener mDirectoryListener;
    private int mLastVisiblePosition = -1;
    private int mInitialPositionY = 0;

    // multi selection support
    private SelectedItems mSelectedItems = new SelectedItems();
    private String mOldTitle = null;

    /**************** construction ******************/
    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment GalleryCursorFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static GalleryCursorFragment newInstance() {
        GalleryCursorFragment fragment = new GalleryCursorFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    public GalleryCursorFragment() {
        debugPrefix = "GalleryCursorFragment#" + (id++)  + " ";
        Global.debugMemory(debugPrefix, "ctor");

        // Required empty public constructor
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, debugPrefix + "()");
        }

    }

    /**************** live-cycle ******************/
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Global.debugMemory(debugPrefix, "onCreate");
        setHasOptionsMenu(true);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mLastVisiblePosition = galleryView.getLastVisiblePosition();
        outState.putInt(INSTANCE_STATE_LAST_VISIBLE_POSITION, mLastVisiblePosition);
        outState.putString(INSTANCE_STATE_SELECTED_ITEM_IDS, this.mSelectedItems.toString());
        outState.putString(INSTANCE_STATE_OLD_TITLE, this.mOldTitle);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Global.debugMemory(debugPrefix, "onCreateView");
        if (savedInstanceState != null) {
            this.mLastVisiblePosition = savedInstanceState.getInt(INSTANCE_STATE_LAST_VISIBLE_POSITION, this.mLastVisiblePosition);
            String old = mSelectedItems.toString();
            mSelectedItems.clear();
            mSelectedItems.parse(savedInstanceState.getString(INSTANCE_STATE_SELECTED_ITEM_IDS, old));
            this.mLastVisiblePosition = savedInstanceState.getString(INSTANCE_STATE_OLD_TITLE, this.mOldTitle);
        }

        // Inflate the layout for this fragment
        View result = inflater.inflate(R.layout.fragment_gallery, container, false);
        galleryView = (GridView) result.findViewById(R.id.gridView);

        galleryAdapter = new GalleryCursorAdapter(this.getActivity(), mGalleryContentQuery, mSelectedItems, debugPrefix);
        galleryAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                if (mLastVisiblePosition > 0) {
                    galleryView.smoothScrollToPosition(mLastVisiblePosition);
                    mLastVisiblePosition = -1;
                }
            }
        });
        galleryView.setAdapter(galleryAdapter);

        galleryView.setLongClickable(true);
        galleryView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int position, long id) {
                return onGalleryLongImageClick((GalleryCursorAdapter.GridCellViewHolder) v.getTag(), position);
            }
        });

        galleryView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                onGalleryImageClick((GalleryCursorAdapter.GridCellViewHolder) v.getTag(), position);
            }
        });

        this.parentPathBar = (LinearLayout) result.findViewById(R.id.parent_owner);
        this.parentPathBarScroller = (HorizontalScrollView) result.findViewById(R.id.parent_scroller);

        this.childPathBar = (LinearLayout) result.findViewById(R.id.child_owner);
        this.childPathBarScroller = (HorizontalScrollView) result.findViewById(R.id.child_scroller);

        reloadDirGuiIfAvailable();

        return result;
    }

    @Override
    public void onAttach(Activity activity) {
        Global.debugMemory(debugPrefix, "onAttach");
        super.onAttach(activity);
        try {
            mGalleryListener = (OnGalleryInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnGalleryInteractionListener");
        }

        try {
            mDirectoryListener = (DirectoryPickerFragment.OnDirectoryInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement DirectoryPickerFragment.OnDirectoryInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        Global.debugMemory(debugPrefix, "onDetach");
        super.onDetach();
        mGalleryListener = null;
        mDirectoryListener = null;
    }

    @Override
    public void onDestroy() {
        Global.debugMemory(debugPrefix, "onDestroy before");
        mGalleryContentQuery = null;
        galleryAdapter.changeCursor(null);
        galleryAdapter = null;
        super.onDestroy();
        System.gc();
        Global.debugMemory(debugPrefix, "onDestroy after");
        // RefWatcher refWatcher = AndroFotoFinderApp.getRefWatcher(getActivity());
        // refWatcher.watch(this);
    }


        /**
         * interface Queryable: Initiates a database requery in the background
         *
         * @param context
         * @param parameters
         */
    @Override
    public void requery(Activity context, QueryParameterParcelable parameters) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, debugPrefix + "requery " + ((parameters != null) ? parameters.toSqlString() : null));
        }

        this.mGalleryContentQuery = parameters;

        galleryAdapter.requery(this.getActivity(), mGalleryContentQuery);
    }

    @Override
    public String toString() {
        return debugPrefix + this.galleryAdapter;
    }

    /*********************** local helper *******************************************/
    /** an Image in the FotoGallery was clicked */
    private void onGalleryImageClick(final GalleryCursorAdapter.GridCellViewHolder holder, int position) {
        if ((!handleMultiselectionClick(holder)) && (mGalleryListener != null) && (mGalleryContentQuery != null)) {
            QueryParameterParcelable imageQuery = new QueryParameterParcelable(mGalleryContentQuery);

            if (holder.filter != null) {
                FotoSql.addWhereFilter(imageQuery, holder.filter);
            }
            long imageID = holder.imageID;
            mGalleryListener.onGalleryImageClick(imageID, getUri(imageID), position);
        }
    }

    /** converts imageID to content-uri */
    private Uri getUri(long imageID) {
        return Uri.parse(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString() + "/" + imageID);
    }

    /****************** path navigation *************************/

    private Directory mDirectoryRoot = null;
    private int mDirQueryID = 0;

    private String mCurrentPath = null;

    /** Defines Directory Navigation */
    @Override
    public void defineDirectoryNavigation(Directory root, int dirTypId, String initialAbsolutePath) {
        mDirectoryRoot = root;
        mDirQueryID = dirTypId;
        navigateTo(initialAbsolutePath);

    }

    /** Set curent selection to absolutePath */
    @Override
    public void navigateTo(String absolutePath) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, debugPrefix + " navigateTo : " + absolutePath);
        }

        mCurrentPath = absolutePath;
        reloadDirGuiIfAvailable();
        // requeryGallery(); done by owning activity
    }

    private void reloadDirGuiIfAvailable() {
        if ((mDirectoryRoot != null) && (mCurrentPath != null) && (parentPathBar != null)) {

            parentPathBar.removeAllViews();
            childPathBar.removeAllViews();

            Directory selectedChild = mDirectoryRoot.find(mCurrentPath);
            if (selectedChild == null) selectedChild = mDirectoryRoot;

            Button first = null;
            Directory current = selectedChild;
            while (current.getParent() != null) {
                Button button = createPathButton(current);
                // add parent left to chlild
                // gui order root/../child.parent/child
                parentPathBar.addView(button, 0);
                if (first == null) first = button;
                current = current.getParent();
            }

            // scroll to right where deepest child is
            if (first != null) parentPathBarScroller.requestChildFocus(parentPathBar, first);

            List<Directory> children = selectedChild.getChildren();
            if (children != null) {
                for (Directory child : children) {
                    Button button = createPathButton(child);
                    childPathBar.addView(button);
                }
            }
        }
    }

    private Button createPathButton(Directory currentDir) {
        Button result = new Button(getActivity());
        result.setTag(currentDir);
        result.setText(getDirectoryDisplayText(null, currentDir, (FotoViewerParameter.includeSubItems) ? Directory.OPT_SUB_ITEM : Directory.OPT_ITEM));

        result.setOnClickListener(onPathButtonClickListener);
        return result;
    }

    /** path/directory was clicked */
    private View.OnClickListener onPathButtonClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onPathButtonClick((Directory) v.getTag());
        }
    };

    /** path/directory was clicked */
    private void onPathButtonClick(Directory newSelection) {
        if ((mDirectoryListener != null) && (newSelection != null)) {
            mCurrentPath = newSelection.getAbsolute();
            mDirectoryListener.onDirectoryPick(mCurrentPath, this.mDirQueryID);
        }
    }

    /** getFrom tree display text */
    private static String getDirectoryDisplayText(String prefix, Directory directory, int options) {
        StringBuilder result = new StringBuilder();
        if (prefix != null) result.append(prefix);
        result.append(directory.getRelPath()).append(" ");
        Directory.appendCount(result, directory, options);
        return result.toString();
    }


    /********************** Multi selection support ***********************************************************/
    private boolean mMustReplaceMenue = false;

    /** return true if multiselection is active */
    private boolean handleMultiselectionClick(GalleryCursorAdapter.GridCellViewHolder holder) {
        if (!mSelectedItems.isEmpty()) {
            long imageID = holder.imageID;
            holder.icon.setVisibility((mSelectedItems.toggle(imageID)) ? View.VISIBLE : View.GONE);
            updateActionbarMultiSelection();
            return true;
        }
        return false;
    }

    private boolean onGalleryLongImageClick(final GalleryCursorAdapter.GridCellViewHolder holder, int position) {
        if (mSelectedItems.isEmpty()) {
            mOldTitle = getActivity().getTitle().toString();
            mMustReplaceMenue = true;

            // must replace OptionsMenu
            getActivity().invalidateOptionsMenu();
        }
        mSelectedItems.add(holder.imageID);
        holder.icon.setVisibility(View.VISIBLE);
        updateActionbarMultiSelection();
        return true;
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (mMustReplaceMenue) {
            menu.clear();
            MenuInflater inflater = getActivity().getMenuInflater();
            inflater.inflate(R.menu.menu_gallery_multiselect, menu);
            mMustReplaceMenue = false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.cmd_cancel:
                cancelMultiSelection();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    private void cancelMultiSelection() {
        mSelectedItems.clear();

        for (int i = galleryView.getChildCount() - 1; i >= 0; i--)
        {
            GalleryCursorAdapter.GridCellViewHolder holder =  (GalleryCursorAdapter.GridCellViewHolder) galleryView.getChildAt(i).getTag();
            if (holder != null) {
                holder.icon.setVisibility(View.GONE);
            }
        }
        updateActionbarMultiSelection();
    }

    private void updateActionbarMultiSelection() {
        String newTitle;
        if (mSelectedItems.isEmpty() && (mOldTitle != null)) {
            // last is deselected. Restore title and menu;
            newTitle = mOldTitle;
            mOldTitle = null;

            getActivity().invalidateOptionsMenu();
        } else {
            newTitle = getActivity().getString(R.string.title_multiselection, mSelectedItems.size());
        }
        getActivity().setTitle(newTitle);
    }


}
