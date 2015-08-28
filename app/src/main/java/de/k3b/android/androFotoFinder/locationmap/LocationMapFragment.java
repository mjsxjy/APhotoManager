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
 
package de.k3b.android.androFotoFinder.locationmap;


import android.app.Activity;
import android.app.DialogFragment;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.app.Fragment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;


import org.osmdroid.ResourceProxy;
import org.osmdroid.api.*;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.util.BoundingBoxE6;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.OverlayManager;

import java.util.HashMap;
import java.util.List;
import java.util.Stack;

import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.androFotoFinder.queries.GalleryFilterParameterParcelable;
import de.k3b.android.androFotoFinder.queries.QueryParameterParcelable;
import de.k3b.android.osmdroid.DefaultResourceProxyImplEx;
import de.k3b.android.osmdroid.FolderOverlay;
import de.k3b.android.osmdroid.GuestureOverlay;
import de.k3b.android.osmdroid.MarkerBase;
import de.k3b.android.osmdroid.ZoomUtil;
import de.k3b.database.SelectedItems;
import de.k3b.io.GeoRectangle;
import de.k3b.io.IGeoRectangle;

/**
 * A fragment to display Foto locations in a geofrafic map.
 * A location-area can be picked for filtering.
 * A simple {@link Fragment} subclass.
 */
public class LocationMapFragment extends DialogFragment {

    private static final String STATE_LAST_VIEWPORT = "LAST_VIEWPORT";
    private static final int NO_ZOOM = ZoomUtil.NO_ZOOM;
    // for debugging
    private static int sId = 1;
    private final String mDebugPrefix;

    private MapView mMapView;
    private SeekBar mZoomBar;
    private ImageView mImage;
    private DefaultResourceProxyImplEx mResourceProxy;

    /** contain the markers with itmen-count that gets recalculated on every map move/zoom */
    private FolderOverlay mFolderOverlaySummaryMarker;
    private FolderOverlay mFolderOverlaySelectionMarker;

    // api to fragment owner
    private OnDirectoryInteractionListener mDirectoryListener;



    /**
     * setCenterZoom does not work in onCreate() because getHeight() and getWidth() are not calculated yet and return 0;
     * setCenterZoom must be set later when getHeight() and getWith() are known (i.e. in onWindowFocusChanged()).
     * <p/>
     * see http://stackoverflow.com/questions/10411975/how-to-get-the-width-and-height-of-an-image-view-in-android/10412209#10412209
     */
    private BoundingBoxE6 mDelayedZoomToBoundingBox = null;
    private int mDelayedZoomLevel = NO_ZOOM;
    private boolean mIsInitialized = false;

    private GalleryFilterParameterParcelable mRootFilter;

    public LocationMapFragment() {
        // Required empty public constructor
        mDebugPrefix = "LocationMapFragment#" + (sId++)  + " ";
        Global.debugMemory(mDebugPrefix, "ctor");
        // Required empty public constructor
        if (Global.debugEnabled) {
            Log.d(Global.LOG_CONTEXT, mDebugPrefix + "()");
        }
    }

    @Override public void onDestroy() {
        if (mCurrentSummaryMarkerLoader != null) mCurrentSummaryMarkerLoader.cancel(false);
        mCurrentSummaryMarkerLoader = null;

        if (mMarkerRecycler != null) mMarkerRecycler.empty();
        super.onDestroy();
        // RefWatcher refWatcher = AndroFotoFinderApp.getRefWatcher(getActivity());
        // refWatcher.watch(this);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mDirectoryListener = (OnDirectoryInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnDirectoryInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mDirectoryListener = null;
    }

    /** on ratation save current selelected view port */
    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putParcelable(STATE_LAST_VIEWPORT, this.mMapView.getBoundingBox());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        BoundingBoxE6 boundingBoxE6 = null;
        /** after ratation restore selelected view port */
        if (savedInstanceState != null) {
            boundingBoxE6 =  savedInstanceState.getParcelable(STATE_LAST_VIEWPORT);
        }
        // if not initialized from outside show the world
        if (boundingBoxE6 == null) {
            boundingBoxE6 = new BoundingBoxE6(80000000, 170000000, -80000000, -170000000);
        }
        zoomToBoundingBox(boundingBoxE6 , NO_ZOOM);

        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_location_map, container, false);

        mMapView = (MapView) view.findViewById(R.id.mapview);
        this.mImage = (ImageView) view.findViewById(R.id.image);
        this.mImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mImage.setVisibility(View.GONE);
            }
        });
        createZoomBar(view);
        mMapView.setMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                reloadSummaryMarker();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                mZoomBar.setProgress(mMapView.getZoomLevel());

                reloadSummaryMarker();
                return false;
            }
        });

        mResourceProxy = new DefaultResourceProxyImplEx(getActivity().getApplicationContext());

        final List<Overlay> overlays = this.mMapView.getOverlays();

        this.mSelectionMarker = getActivity().getResources().getDrawable(R.drawable.marker_blue);
        mFolderOverlaySummaryMarker = createFolderOverlay(overlays);

        mFolderOverlaySelectionMarker = createFolderOverlay(overlays);

        overlays.add(new GuestureOverlay(getActivity()));

        mMapView.setMultiTouchControls(true);


        // mFolderOverlay.add(createMarker(mMapView, ...));

        if (getShowsDialog()) {
            Button cmdCancel = (Button) view.findViewById(R.id.cmd_cancel);
            if (cmdCancel != null) {
                // only available on tablets.
                // on small screen it would block the zoom out button
                cmdCancel.setVisibility(View.VISIBLE);
                cmdCancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        dismiss();
                    }
                });
            }

            Button cmdOk = (Button) view.findViewById(R.id.ok);
            cmdOk.setVisibility(View.VISIBLE);
            cmdOk.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onOk();
                }
            });

            String title = getActivity().getString(
                    R.string.action_area_title);
            getDialog().setTitle(title);

        }


        mMapView.addOnFirstLayoutListener(new MapView.OnFirstLayoutListener() {
            @Override
            public void onFirstLayout(View v, int left, int top, int right, int bottom) {
                mIsInitialized = true;
                zoomToBoundingBox(mDelayedZoomToBoundingBox, mDelayedZoomLevel);
                mDelayedZoomToBoundingBox = null;
                mDelayedZoomLevel = NO_ZOOM;
            }
        });

        reloadSelectionMarker();
        return view;
    }

    private void onOk() {
        if (mDirectoryListener != null) {
            IGeoRectangle result = getGeoRectangle(mMapView.getBoundingBox());
            if (Global.debugEnabled) {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + "onOk: " + result);
            }

            mDirectoryListener.onDirectoryPick(result.toString(), FotoSql.QUERY_TYPE_GROUP_PLACE_MAP);
            dismiss();
        }
    }

    private void createZoomBar(View view) {
        mMapView.setBuiltInZoomControls(true);

        mZoomBar = (SeekBar) view.findViewById(R.id.zoomBar);

        mZoomBar.setMax(mMapView.getMaxZoomLevel() - mMapView.getMinZoomLevel());
        mZoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser)
                    mMapView.getController().setZoom(progress - mMapView.getMinZoomLevel());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }


    private FolderOverlay createFolderOverlay(List<Overlay> overlays) {
        FolderOverlay result = new FolderOverlay(this.getActivity());
        overlays.add(result);

        return result;
    }

    public void defineNavigation(GalleryFilterParameterParcelable rootFilter, GeoRectangle rectangle, int zoomlevel, SelectedItems selectedItems) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + "defineNavigation: " + rectangle + ";z=" + zoomlevel);
        }

        this.mRootFilter = rootFilter;
        this.mSelectedItems = selectedItems;

        if (!Double.isNaN(rectangle.getLatitudeMin())) {
            BoundingBoxE6 boundingBox = new BoundingBoxE6(
                    rectangle.getLatitudeMax(),
                    rectangle.getLogituedMin(),
                    rectangle.getLatitudeMin(),
                    rectangle.getLogituedMax());

            zoomToBoundingBox(boundingBox, zoomlevel);
        }

        if (rootFilter != null) {
            reloadSummaryMarker();
        }
    }

    private void zoomToBoundingBox(BoundingBoxE6 boundingBox, int zoomLevel) {
        if (boundingBox != null) {
            if (mIsInitialized) {
                // if map is already initialized
                GeoPoint min = new GeoPoint(boundingBox.getLatSouthE6(), boundingBox.getLonWestE6());

                if (zoomLevel != NO_ZOOM) {
                    ZoomUtil.zoomTo(this.mMapView, zoomLevel, min, null);
                } else {
                    GeoPoint max = new GeoPoint(boundingBox.getLatNorthE6(), boundingBox.getLonEastE6());
                    ZoomUtil.zoomTo(this.mMapView, ZoomUtil.NO_ZOOM, min, max);
                    // this.mMapView.zoomToBoundingBox(boundingBox); this is to inexact
                }
                if (Global.debugEnabled) {
                    Log.i(Global.LOG_CONTEXT, mDebugPrefix + "zoomToBoundingBox(" + boundingBox
                            + ") => " + mMapView.getBoundingBox() + "; z=" + mMapView.getZoomLevel());
                }
            } else {
                // map not initialized yet. do it later.
                this.mDelayedZoomToBoundingBox = boundingBox;
                this.mDelayedZoomLevel = zoomLevel;
            }
        }
    }

    /** all marker clicks will be delegated to LocationMapFragment#onMarkerClicked() */
    private class FotoMarker extends MarkerBase<Object> {

        public FotoMarker(ResourceProxy pResourceProxy) {
            super(pResourceProxy);
        }

        /**
         * @return true if click was handeled.
         */
        @Override
        protected boolean onMarkerClicked(MapView mapView, int markerId, IGeoPoint makerPosition, Object markerData) {
            return LocationMapFragment.this.onMarkerClicked(markerId, makerPosition, markerData);
        }
    }

    private void reloadSummaryMarker() {
        if (mMapView.getHeight() > 0) {
            // initialized
            if (mCurrentSummaryMarkerLoader == null) {
                // not active yet
                List<Overlay> oldItems = mFolderOverlaySummaryMarker.getItems();

                mLastZoom = this.mMapView.getZoomLevel();
                double groupingFactor = getGroupingFactor(mLastZoom);
                BoundingBoxE6 world = this.mMapView.getBoundingBox();

                reloadSummaryMarker(world, groupingFactor, oldItems);
            } else {
                mSummaryMarkerPendingLoads++;
            }
        }
    }

    private void reloadSummaryMarker(BoundingBoxE6 latLonArea, double groupingFactor, List<Overlay> oldItems) {
        QueryParameterParcelable query = FotoSql.getQueryGroupByPlace(groupingFactor);
        query.clearWhere();

        if (this.mRootFilter != null) {
            FotoSql.setWhereFilter(query, this.mRootFilter);
        }

        // delta: make the grouping area a little bit bigger than the viewport
        // so that counts at the borders are correct.
        double delta = (groupingFactor > 0) ? (2.0 / groupingFactor) : 0.0;
        IGeoRectangle rect = getGeoRectangle(latLonArea);
        FotoSql.addWhereFilteLatLon(query
                , rect.getLatitudeMin() - delta
                , rect.getLatitudeMax() + delta
                , rect.getLogituedMin() - delta
                , rect.getLogituedMax() + delta);

        mCurrentSummaryMarkerLoader = new SummaryMarkerLoaderTask(createHashMap(oldItems));
        mCurrentSummaryMarkerLoader.execute(query);
    }

    private IGeoRectangle getGeoRectangle(BoundingBoxE6 boundingBox) {
        GeoRectangle result = new GeoRectangle();
        result.setLatitude(boundingBox.getLatSouthE6() * 1E-6, boundingBox.getLatNorthE6() * 1E-6);
        result.setLogitude(boundingBox.getLonWestE6() * 1E-6, boundingBox.getLonEastE6() * 1E-6);

        return result;
    }

    /** translates map-zoomlevel to groupfactor
     * that tells sql how geo-points are grouped together.
     */
    private double getGroupingFactor(int zoomlevel) {
        // todo
        return FotoSql.getGroupFactor(zoomlevel);
    }

    // for debugginc
    private static int sInstanceCountFotoLoader = 1;

    /** caching support: if zoom level changes the cached items become invalid
     * because the marker clustering is different */
    private int mLastZoom = NO_ZOOM;

    /** how much mCurrentSummaryMarkerLoader are tirggerd while task is loading */
    private int mSummaryMarkerPendingLoads = 0;

    /** The factory LocationMapFragment.SummaryMarkerLoaderTask#createMarker() tries to recycle old
     *     unused Fotomarkers before creating new */
    private Stack<FotoMarker> mMarkerRecycler = new Stack<FotoMarker>();

    /** To allow canceling of loading task. There are 0 or one tasks running at a time */
    private SummaryMarkerLoaderTask mCurrentSummaryMarkerLoader = null;

    /** to load summary marker with numbers in the icons */
    private class SummaryMarkerLoaderTask extends MarkerLoaderTaskWithRecycling<FotoMarker> {
        public SummaryMarkerLoaderTask(HashMap<Integer, FotoMarker> oldItems) {
            super(getActivity(), LocationMapFragment.this.mDebugPrefix + "-SummaryMarkerLoaderTask#" + (sInstanceCountFotoLoader++) + "-", mMarkerRecycler, oldItems);
        }

        @NonNull
        protected FotoMarker createNewMarker() {
            return new FotoMarker(mResourceProxy);
        }

        // This is called when doInBackground() is finished
        @Override
        protected void onPostExecute(OverlayManager result) {
            boolean zoomLevelChanged = mMapView.getZoomLevel() != mLastZoom;

            if (isCancelled()) {
                onLoadFinishedSummaryMarker(null, zoomLevelChanged);
            } else {
                onLoadFinishedSummaryMarker(result, zoomLevelChanged);

                recyleItems(zoomLevelChanged, mOldItems);
            }

            mOldItems.clear();
            mOldItems = null;
            int recyclerSize = mMarkerRecycler.size();
            if (mStatus != null) {
                mStatus.append("\n\tRecycler: ").append(mRecyclerSizeBefore).append(",")
                        .append(mRecyclerSizeAfter).append(",").append(recyclerSize)
                        .append("\n\t").append(mMapView.getBoundingBox())
                        .append(", z= ").append(mMapView.getZoomLevel())
                        .append("\n\tPendingLoads").append(mSummaryMarkerPendingLoads);
                if (Global.debugEnabledSql) {
                    Log.w(Global.LOG_CONTEXT, mDebugPrefix + mStatus);
                } else {
                    Log.i(Global.LOG_CONTEXT, mDebugPrefix + mStatus);
                }
            }

            // in the meantime the mapview has moved: must recalculate again.
            mCurrentSummaryMarkerLoader = null;
            if (mSummaryMarkerPendingLoads > 0) {
                mSummaryMarkerPendingLoads = 0;
                reloadSummaryMarker();
            }
        }

    } // class SummaryMarkerLoaderTask

    /** gets called when SummaryMarkerLoaderTask has finished.
     *
     * @param result null if there was an error
     * @param zoomLevelChanged
     */
    private void onLoadFinishedSummaryMarker(OverlayManager result, boolean zoomLevelChanged) {
        StringBuilder dbg = (Global.debugEnabledSql || Global.debugEnabled) ? new StringBuilder() : null;
        if (dbg != null) {
            int found = (result != null) ? result.size() : 0;
            dbg.append(mDebugPrefix).append("onLoadFinishedSummaryMarker() markers created: ").append(found);
        }

        if (result != null) {
            OverlayManager old = mFolderOverlaySummaryMarker.setOverlayManager(result);
            if (old != null) {
                if (dbg != null) {
                    dbg.append(mDebugPrefix).append(" previous : : ").append(old.size());
                }
                if (zoomLevelChanged) {
                    if (dbg != null) dbg
                            .append(" zoomLevelChanged - recycling : ")
                            .append(old.size())
                            .append(" items");

                    for (Overlay item : old) {
                        mMarkerRecycler.add((FotoMarker) item);
                    }
                }
                old.onDetach(this.mMapView);
                old.clear();
            }
            this.mMapView.invalidate();
        }
        if (dbg != null) {
            Log.d(Global.LOG_CONTEXT, dbg.toString());
        }
    }

    /**
     * @return true if click was handeled.
     */
    private boolean onMarkerClicked(int markerId, IGeoPoint makerPosition, Object markerData) {
        this.mImage.setImageBitmap(getBitmap(markerId));
        this.mImage.setVisibility(View.VISIBLE);
        return true; // TODO
    }

    private Bitmap getBitmap(int id) {
        final Bitmap thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
                getActivity().getContentResolver(),
                id,
                MediaStore.Images.Thumbnails.MICRO_KIND,
                new BitmapFactory.Options());

        return thumbnail;
    }


    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p/>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnDirectoryInteractionListener {
        /** called when upresses "OK" button */
        void onDirectoryPick(String selectedAbsolutePath, int queryTypeId);
    }

    /**************************** Support for non-clustered selected items ********************/

    private SelectedItems mSelectedItems = null;

    /** To allow canceling of loading task. There are 0 or one tasks running at a time */
    private SelectionMarkerLoaderTask mCurrentSelectionMarkerLoader = null;

    private Drawable mSelectionMarker;

    /** to load markers for current selected items */
    private class SelectionMarkerLoaderTask extends MarkerLoaderTaskWithRecycling<FotoMarker> {
        public SelectionMarkerLoaderTask(HashMap<Integer, FotoMarker> oldItems) {
            super(getActivity(), LocationMapFragment.this.mDebugPrefix + "-SelectionMarkerLoaderTask#" + (sInstanceCountFotoLoader++) + "-", mMarkerRecycler, oldItems);
        }

        @NonNull
        protected FotoMarker createNewMarker() {
            return new FotoMarker(mResourceProxy);
        }

        // This is called when doInBackground() is finished
        @Override
        protected void onPostExecute(OverlayManager result) {
            if (isCancelled()) {
                onLoadFinishedSelection(null);
            } else {
                onLoadFinishedSelection(result);

                recyleItems(false, mOldItems);
            }

            mOldItems.clear();
            mOldItems = null;
            int recyclerSize = mMarkerRecycler.size();
            if (mStatus != null) {
                mStatus.append("\n\tRecycler: ").append(mRecyclerSizeBefore).append(",")
                        .append(mRecyclerSizeAfter).append(",").append(recyclerSize);
                if (Global.debugEnabledSql) {
                    Log.w(Global.LOG_CONTEXT, mDebugPrefix + mStatus);
                } else {
                    Log.i(Global.LOG_CONTEXT, mDebugPrefix + mStatus);
                }
            }
        }

        protected BitmapDrawable createIcon(String iconText) {
            return (BitmapDrawable) mSelectionMarker;
        }

    } // class SelectionMarkerLoaderTask

    private void reloadSelectionMarker() {
        if ((mSelectedItems != null) && (!mSelectedItems.isEmpty())) {
            if (mCurrentSelectionMarkerLoader != null) {
                mCurrentSelectionMarkerLoader.cancel(false);
                mCurrentSelectionMarkerLoader = null;
            }

            List<Overlay> oldItems = mFolderOverlaySelectionMarker.getItems();

            QueryParameterParcelable query = new QueryParameterParcelable(FotoSql.queryGps);
            FotoSql.addWhereSelection(query, mSelectedItems);

            mCurrentSelectionMarkerLoader = new SelectionMarkerLoaderTask(createHashMap(oldItems));
            mCurrentSelectionMarkerLoader.execute(query);
        }
    }

    @NonNull
    private HashMap<Integer, FotoMarker> createHashMap(List<Overlay> oldItems) {
        HashMap<Integer, FotoMarker> oldItemsHash = new HashMap<Integer, FotoMarker>();
        for (Overlay o : oldItems) {
            FotoMarker marker = (FotoMarker) o;
            oldItemsHash.put(marker.getID(), marker);
        }
        return oldItemsHash;
    }

    /** gets called when MarkerLoaderTask has finished.
     *
     * @param result null if there was an error
     */
    private void onLoadFinishedSelection(OverlayManager result) {
        mCurrentSelectionMarkerLoader = null;
        StringBuilder dbg = (Global.debugEnabledSql || Global.debugEnabled) ? new StringBuilder() : null;
        if (dbg != null) {
            int found = (result != null) ? result.size() : 0;
            dbg.append(mDebugPrefix).append("onLoadFinishedSelection() markers created: ").append(found);
        }

        if (result != null) {
            OverlayManager old = mFolderOverlaySelectionMarker.setOverlayManager(result);
            if (old != null) {
                if (dbg != null) {
                    dbg.append(mDebugPrefix).append(" previous : : ").append(old.size());
                }
                old.onDetach(this.mMapView);
                old.clear();
            }
            this.mMapView.invalidate();
        }
        if (dbg != null) {
            Log.d(Global.LOG_CONTEXT, dbg.toString());
        }
    }

}
