package ru.vvdev.yamap.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.yandex.mapkit.Animation;
import com.yandex.mapkit.MapKit;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.geometry.BoundingBox;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polyline;
import com.yandex.mapkit.geometry.SubpolylineHelper;
import com.yandex.mapkit.layers.ObjectEvent;
import com.yandex.mapkit.map.CameraListener;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.CameraUpdateReason;
import com.yandex.mapkit.map.CircleMapObject;
import com.yandex.mapkit.map.InputListener;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.map.PolygonMapObject;
import com.yandex.mapkit.map.PolylineMapObject;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.mapkit.user_location.UserLocationLayer;
import com.yandex.mapkit.user_location.UserLocationObjectListener;
import com.yandex.mapkit.user_location.UserLocationView;
import com.yandex.mapkit.user_location.internal.UserLocationLayerBinding;
import com.yandex.runtime.Error;
import com.yandex.runtime.image.ImageProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import ru.vvdev.yamap.models.ReactMapObject;
import ru.vvdev.yamap.utils.Callback;
import ru.vvdev.yamap.utils.ImageLoader;

public class YamapView extends MapView implements UserLocationObjectListener, CameraListener, InputListener {
    private String userLocationIcon = "";
    private Bitmap userLocationBitmap = null;

    private UserLocationLayer userLocationLayer = null;
    private int userLocationAccuracyFillColor = 0;
    private int userLocationAccuracyStrokeColor = 0;
    private float userLocationAccuracyStrokeWidth = 0.f;
    private List<ReactMapObject> childs = new ArrayList<>();

    // location
    private UserLocationView userLocationView = null;

    public YamapView(Context context) {
        super(context);
        getMap().addCameraListener(this);
        getMap().addInputListener(this);
        getMap().setRotateGesturesEnabled(false);
        getMap().setTiltGesturesEnabled(false);
    }

    // ref methods
    public void setCenter(CameraPosition position, float duration, int animation) {
        if (duration > 0) {
            Animation.Type anim = animation == 0 ? Animation.Type.SMOOTH : Animation.Type.LINEAR;
            getMap().move(position, new Animation(anim, duration), null);
        } else {
            getMap().move(position);
        }
    }

    private WritableMap positionToJSON(CameraPosition position) {
        WritableMap cameraPosition = Arguments.createMap();
        Point point = position.getTarget();
        cameraPosition.putDouble("azimuth", position.getAzimuth());
        cameraPosition.putDouble("tilt", position.getTilt());
        cameraPosition.putDouble("zoom", position.getZoom());
        WritableMap target = Arguments.createMap();
        target.putDouble("lat", point.getLatitude());
        target.putDouble("lon", point.getLongitude());
        cameraPosition.putMap("point", target);
        return cameraPosition;
    }

    public void emitCameraPositionToJS(String id) {
        CameraPosition position = getMap().getCameraPosition();
        WritableMap cameraPosition = positionToJSON(position);
        cameraPosition.putString("id", id);
        ReactContext reactContext = (ReactContext) getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(getId(), "cameraPosition", cameraPosition);
    }

    public void setZoom(Float zoom, float duration, int animation) {
        CameraPosition prevPosition = getMap().getCameraPosition();
        CameraPosition position = new CameraPosition(prevPosition.getTarget(), zoom, prevPosition.getAzimuth(), prevPosition.getTilt());
        setCenter(position, duration, animation);
    }

    public void findRoutes(ArrayList<Point> points, final ArrayList<String> vehicles, final String id) {
    }

    public void fitAllMarkers() {
        ArrayList<Point> lastKnownMarkers = new ArrayList<>();
        for (int i = 0; i < childs.size(); ++i) {
            ReactMapObject obj = childs.get(i);
            if (obj instanceof YamapMarker) {
                YamapMarker marker = (YamapMarker) obj;
                lastKnownMarkers.add(marker.point);
            }
        }
        // todo[0]: добавить параметры анимации и дефолтного зума (для одного маркера)
        if (lastKnownMarkers.size() == 0) {
            return;
        }
        if (lastKnownMarkers.size() == 1) {
            Point center = new Point(lastKnownMarkers.get(0).getLatitude(), lastKnownMarkers.get(0).getLongitude());
            getMap().move(new CameraPosition(center, 15, 0, 0));
            return;
        }
        double minLon = lastKnownMarkers.get(0).getLongitude();
        double maxLon = lastKnownMarkers.get(0).getLongitude();
        double minLat = lastKnownMarkers.get(0).getLatitude();
        double maxLat = lastKnownMarkers.get(0).getLatitude();
        for (int i = 0; i < lastKnownMarkers.size(); i++) {
            if (lastKnownMarkers.get(i).getLongitude() > maxLon) {
                maxLon = lastKnownMarkers.get(i).getLongitude();
            }
            if (lastKnownMarkers.get(i).getLongitude() < minLon) {
                minLon = lastKnownMarkers.get(i).getLongitude();
            }
            if (lastKnownMarkers.get(i).getLatitude() > maxLat) {
                maxLat = lastKnownMarkers.get(i).getLatitude();
            }
            if (lastKnownMarkers.get(i).getLatitude() < minLat) {
                minLat = lastKnownMarkers.get(i).getLatitude();
            }
        }
        Point southWest = new Point(minLat, minLon);
        Point northEast = new Point(maxLat, maxLon);

        BoundingBox boundingBox = new BoundingBox(southWest, northEast);
        CameraPosition cameraPosition = getMap().cameraPosition(boundingBox);
        cameraPosition = new CameraPosition(cameraPosition.getTarget(), cameraPosition.getZoom() - 0.8f, cameraPosition.getAzimuth(), cameraPosition.getTilt());
        getMap().move(cameraPosition, new Animation(Animation.Type.SMOOTH, 0.7f), null);
    }

    // props
    public void setUserLocationIcon(final String iconSource) {
        // todo[0]: можно устанавливать разные иконки на покой и движение. Дополнительно можно устанавливать стиль иконки, например scale
        userLocationIcon = iconSource;
        ImageLoader.DownloadImageBitmap(getContext(), iconSource, new Callback<Bitmap>() {
            @Override
            public void invoke(Bitmap bitmap) {
                if (iconSource.equals(userLocationIcon)) {
                    userLocationBitmap = bitmap;
                    updateUserLocationIcon();
                }
            }
        });
    }

    public void setUserLocationAccuracyFillColor(int color) {
        userLocationAccuracyFillColor = color;
        updateUserLocationIcon();
    }

    public void setUserLocationAccuracyStrokeColor(int color) {
        userLocationAccuracyStrokeColor = color;
        updateUserLocationIcon();
    }

    public void setUserLocationAccuracyStrokeWidth(float width) {
        userLocationAccuracyStrokeWidth = width;
        updateUserLocationIcon();
    }

    public void setMapStyle(@Nullable String style) {
        if (style != null) {
            getMap().setMapStyle(style);
        }
    }

    public void setNightMode(Boolean nightMode) {
        getMap().setNightModeEnabled(nightMode);
    }

    public void setShowUserPosition(Boolean show) {
        if (userLocationLayer == null) {

            MapKit mapKit = MapKitFactory.getInstance();
            userLocationLayer = mapKit.createUserLocationLayer(this.getMapWindow());
        }
        if (show) {
            userLocationLayer.setObjectListener(this);
            userLocationLayer.setVisible(true);
            userLocationLayer.setHeadingEnabled(true);
        } else {
            userLocationLayer.setVisible(false);
            userLocationLayer.setHeadingEnabled(false);
            userLocationLayer.setObjectListener(null);
        }
        //TODO show user position
    }


    private String formatColor(int color) {
        return String.format("#%06X", (0xFFFFFF & color));
    }

    // children
    public void addFeature(View child, int index) {
        if (child instanceof YamapPolygon) {
            YamapPolygon _child = (YamapPolygon) child;
            PolygonMapObject obj = getMap().getMapObjects().addPolygon(_child.polygon);
            _child.setMapObject(obj);
            childs.add(_child);
        } else if (child instanceof YamapPolyline) {
            YamapPolyline _child = (YamapPolyline) child;
            PolylineMapObject obj = getMap().getMapObjects().addPolyline(_child.polyline);
            _child.setMapObject(obj);
            childs.add(_child);
        } else if (child instanceof YamapMarker) {
            YamapMarker _child = (YamapMarker) child;
            PlacemarkMapObject obj = getMap().getMapObjects().addPlacemark(_child.point);
            _child.setMapObject(obj);
            childs.add(_child);
        } else if (child instanceof YamapCircle) {
            YamapCircle _child = (YamapCircle) child;
            CircleMapObject obj = getMap().getMapObjects().addCircle(_child.circle, 0, 0.f, 0);
            _child.setMapObject(obj);
            childs.add(_child);
        }
    }

    public void removeChild(int index) {
        if (index < childs.size()) {
            ReactMapObject child = childs.remove(index);
            getMap().getMapObjects().remove(child.getMapObject());
        }
    }

    // location listener implementation
    @Override
    public void onObjectAdded(@Nonnull UserLocationView _userLocationView) {
        userLocationView = _userLocationView;
        updateUserLocationIcon();
    }

    @Override
    public void onObjectRemoved(@Nonnull UserLocationView userLocationView) {
    }

    @Override
    public void onObjectUpdated(@Nonnull UserLocationView _userLocationView, @Nonnull ObjectEvent objectEvent) {
        userLocationView = _userLocationView;
        updateUserLocationIcon();
    }

    private void updateUserLocationIcon() {
        if (userLocationView != null) {
            PlacemarkMapObject pin = userLocationView.getPin();
            PlacemarkMapObject arrow = userLocationView.getArrow();
            if (userLocationBitmap != null) {
                pin.setIcon(ImageProvider.fromBitmap(userLocationBitmap));
                arrow.setIcon(ImageProvider.fromBitmap(userLocationBitmap));
            }
            CircleMapObject circle = userLocationView.getAccuracyCircle();
            if (userLocationAccuracyFillColor != 0) {
                circle.setFillColor(userLocationAccuracyFillColor);
            }
            if (userLocationAccuracyStrokeColor != 0) {
                circle.setStrokeColor(userLocationAccuracyStrokeColor);
            }
            circle.setStrokeWidth(userLocationAccuracyStrokeWidth);
        }
    }

    @Override
    public void onMapTap(@NonNull com.yandex.mapkit.map.Map map, @NonNull Point point) {
        WritableMap data = Arguments.createMap();
        data.putDouble("lat", point.getLatitude());
        data.putDouble("lon", point.getLongitude());
        ReactContext reactContext = (ReactContext) getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(getId(), "onMapPress", data);
    }

    @Override
    public void onMapLongTap(@NonNull com.yandex.mapkit.map.Map map, @NonNull Point point) {
        WritableMap data = Arguments.createMap();
        data.putDouble("lat", point.getLatitude());
        data.putDouble("lon", point.getLongitude());
        ReactContext reactContext = (ReactContext) getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(getId(), "onMapLongPress", data);
    }

    @Override
    public void onCameraPositionChanged(@NonNull com.yandex.mapkit.map.Map map, @NonNull CameraPosition cameraPosition, @NonNull CameraUpdateReason cameraUpdateReason, boolean b) {
        WritableMap position = positionToJSON(cameraPosition);
        ReactContext reactContext = (ReactContext) getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(getId(), "cameraPositionChanged", position);
//TODO ADD REASON
    }
}
