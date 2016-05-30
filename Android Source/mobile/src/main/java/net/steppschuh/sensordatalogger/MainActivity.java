package net.steppschuh.sensordatalogger;

import android.app.DialogFragment;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import net.steppschuh.datalogger.data.DataBatch;
import net.steppschuh.datalogger.data.DataChangedListener;
import net.steppschuh.datalogger.data.request.DataRequest;
import net.steppschuh.datalogger.data.request.DataRequestResponse;
import net.steppschuh.datalogger.data.request.SensorDataRequest;
import net.steppschuh.datalogger.logging.TimeTracker;
import net.steppschuh.datalogger.logging.TrackerManager;
import net.steppschuh.datalogger.messaging.GoogleApiMessenger;
import net.steppschuh.datalogger.messaging.ReachabilityChecker;
import net.steppschuh.datalogger.messaging.handler.MessageHandler;
import net.steppschuh.datalogger.messaging.handler.SinglePathMessageHandler;
import net.steppschuh.datalogger.sensor.DeviceSensor;
import net.steppschuh.datalogger.status.ActivityStatus;
import net.steppschuh.datalogger.status.GoogleApiStatus;
import net.steppschuh.datalogger.status.Status;
import net.steppschuh.datalogger.status.StatusUpdateHandler;
import net.steppschuh.datalogger.status.StatusUpdateReceiver;
import net.steppschuh.sensordatalogger.visualization.VisualizationCardData;
import net.steppschuh.sensordatalogger.visualization.VisualizationCardListAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements DataChangedListener, ReachabilityChecker.NodeReachabilityUpdateReceiver, SensorSelectionDialogFragment.SelectedSensorsUpdatedListener {

    private static final String TAG = MainActivity.class.getSimpleName();

    private PhoneApp app;
    private List<MessageHandler> messageHandlers;
    private ActivityStatus status = new ActivityStatus();
    private StatusUpdateHandler statusUpdateHandler;

    private FloatingActionButton floatingActionButton;
    private TextView logTextView;
    private GridView gridView;

    private VisualizationCardListAdapter cardListAdapter;

    private Map<String, SensorDataRequest> sensorDataRequests = new HashMap<>();
    private Map<String, List<DeviceSensor>> selectedSensors = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // get reference to global application
        app = (PhoneApp) getApplicationContext();

        // initialize with context activity if needed
        if (!app.getStatus().isInitialized() || app.getContextActivity() == null) {
            app.initialize(this);
        }

        setupUi();
        setupMessageHandlers();
        setupStatusUpdates();

        status.setInitialized(true);
        status.updated(statusUpdateHandler);
    }

    private void setupUi() {
        setContentView(R.layout.activity_main);

        floatingActionButton = (FloatingActionButton) findViewById(R.id.floadtingActionButton);
        floatingActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSensorSelectionDialog();
            }
        });

        logTextView = (TextView) findViewById(R.id.logText);
        gridView = (GridView) findViewById(R.id.gridView);

        List<VisualizationCardData> visualizationCardData = new ArrayList<>();
        cardListAdapter = new VisualizationCardListAdapter(this, R.id.gridView, visualizationCardData);
        gridView.setAdapter(cardListAdapter);
    }

    private void setupMessageHandlers() {
        messageHandlers = new ArrayList<>();
        messageHandlers.add(getSetStatusMessageHandler());
        messageHandlers.add(getSensorDataRequestResponseMessageHandler());
    }

    private void setupStatusUpdates() {
        statusUpdateHandler = new StatusUpdateHandler();
        statusUpdateHandler.registerStatusUpdateReceiver(new StatusUpdateReceiver() {
            @Override
            public void onStatusUpdated(Status status) {
                app.getStatus().setActivityStatus((ActivityStatus) status);
                app.getStatus().updated(app.getStatusUpdateHandler());
            }
        });

        app.getGoogleApiMessenger().getStatusUpdateHandler().registerStatusUpdateReceiver(new StatusUpdateReceiver() {
            @Override
            public void onStatusUpdated(Status status) {
                GoogleApiStatus googleApiStatus = (GoogleApiStatus) status;
                int nearbyNodes = GoogleApiMessenger.getNearbyNodes(googleApiStatus.getLastConnectedNodes()).size();
                //Toast.makeText(MainActivity.this, "Connected devices: " + nearbyNodes, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        // register message handlers
        for (MessageHandler messageHandler : messageHandlers) {
            app.registerMessageHandler(messageHandler);
        }
        Wearable.MessageApi.addListener(app.getGoogleApiMessenger().getGoogleApiClient(), app);

        // update status
        status.setInForeground(true);
        status.updated(statusUpdateHandler);

        // start data request
        sendSensorEventDataRequests();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // update reachabilities of nearby nodes
        app.getReachabilityChecker().checkReachabilities(this);
    }

    @Override
    protected void onStop() {
        // stop data request
        stopRequestingSensorEventData();

        // let other devices know that the app won't be reachable anymore
        app.getGoogleApiMessenger().sendMessageToNearbyNodes(MessageHandler.PATH_CLOSING, Build.MODEL);

        // unregister reachability callback
        app.getReachabilityChecker().unregisterReachabilityUpdateReceiver(this);

        // unregister message handlers
        for (MessageHandler messageHandler : messageHandlers) {
            app.unregisterMessageHandler(messageHandler);
        }
        Wearable.MessageApi.removeListener(app.getGoogleApiMessenger().getGoogleApiClient(), app);

        // update status
        status.setInForeground(false);
        status.updated(statusUpdateHandler);
        super.onStop();
    }

    /**
     * Will be called when the reachability of a connected @Node
     * (e.g. wearable device) has changed
     */
    @Override
    public void onReachabilityUpdated(String nodeId, boolean isReachable) {
        Log.d(TAG, "Reachability of " + nodeId + " changed to: " + String.valueOf(isReachable));

        // generate a readable message
        String nodeName = app.getGoogleApiMessenger().getNodeName(nodeId);
        String message = isReachable ? getString(R.string.device_connected) : getString(R.string.device_disconnected);
        message = message.replace("[DEVICENAME]", nodeName);

        // notify the user with a @Snackbar
        View parentLayout = findViewById(android.R.id.content);
        Snackbar.make(parentLayout, message, Snackbar.LENGTH_LONG)
                .setDuration(Snackbar.LENGTH_LONG)
                .show();
    }

    /**
     * Will be called when a @Node sends (sensor) @Data to this device
     */
    @Override
    public void onDataChanged(DataBatch dataBatch, String sourceNodeId) {
        renderDataBatch(dataBatch, sourceNodeId);
    }

    /*
     * Message Handlers
     */
    private MessageHandler getSetStatusMessageHandler() {
        return new SinglePathMessageHandler(MessageHandler.PATH_SET_STATUS) {
            @Override
            public void handleMessage(Message message) {
                String sourceNodeId = MessageHandler.getSourceNodeIdFromMessage(message);
                String statusJson = MessageHandler.getDataFromMessageAsString(message);
                Log.d(TAG, "Received status from: " + sourceNodeId + ": " + statusJson);
                logTextView.setText(statusJson);
            }
        };
    }

    private MessageHandler getSensorDataRequestResponseMessageHandler() {
        return new SinglePathMessageHandler(MessageHandler.PATH_SENSOR_DATA_REQUEST_RESPONSE) {
            @Override
            public void handleMessage(final Message message) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // parse response data
                        final String sourceNodeId = MessageHandler.getSourceNodeIdFromMessage(message);
                        final String responseJson = MessageHandler.getDataFromMessageAsString(message);
                        final DataRequestResponse response = DataRequestResponse.fromJson(responseJson);

                        // render data in UI thread
                        Runnable notifyDataChangedRunnable = new Runnable() {
                            @Override
                            public void run() {
                                for (DataBatch dataBatch : response.getDataBatches()) {
                                    onDataChanged(dataBatch, sourceNodeId);
                                }
                            }
                        };
                        new Handler(Looper.getMainLooper()).post(notifyDataChangedRunnable);
                    }
                }).start();
            }
        };
    }

    private void requestStatusUpdateFromConnectedNodes() {
        try {
            Log.v(TAG, "Sending a status update request");
            app.getGoogleApiMessenger().sendMessageToNearbyNodes(MessageHandler.PATH_GET_STATUS, "");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Will create and show a dialog that allows the user to check the @DeviceSensor
     * on each connected node that he wants to stream
     */
    private void showSensorSelectionDialog() {
        SensorSelectionDialogFragment sensorSelectionDialogFragment = new SensorSelectionDialogFragment();
        sensorSelectionDialogFragment.setPreviouslySelectedSensors(selectedSensors);
        sensorSelectionDialogFragment.show(getFragmentManager(), SensorSelectionDialogFragment.class.getSimpleName());
    }

    /**
     * Will be called when the sensor selection dialog has been closed after
     * sensors from all nodes have been selected
     */
    @Override
    public void onSensorsFromAllNodesSelected(Map<String, List<DeviceSensor>> selectedSensors) {
        Log.d(TAG, "Sensors from all nodes selected");
        this.selectedSensors = selectedSensors;
    }

    /**
     * Will be called when the sensor selection dialog has shown @DeviceSensor from
     * the specified node
     */
    @Override
    public void onSensorsFromNodeSelected(String nodeId, List<DeviceSensor> sensors) {
        StringBuilder sb = new StringBuilder("Selected sensors for " + nodeId + ":");
        for (DeviceSensor sensor : sensors) {
            sb.append("\n - " + sensor.getName());
        }
        Log.d(TAG, sb.toString());

        selectedSensors.put(nodeId, sensors);

        SensorDataRequest sensorDataRequest = SensorSelectionDialogFragment.createSensorDataRequest(sensors);
        sensorDataRequest.setSourceNodeId(app.getGoogleApiMessenger().getLocalNodeId());
        sensorDataRequests.put(nodeId, sensorDataRequest);

        sendSensorEventDataRequests();
        removeUnneededVisualizationCards();
    }

    /**
     * Will be called if the sensor selection dialog has been canceled during
     * the sensor selection of any node
     */
    @Override
    public void onSensorSelectionCanceled(DialogFragment dialog) {
        Log.d(TAG, "Sensor selection canceled");
    }

    /**
     * Returns true if the app is requesting sensor data from
     * the local or any connected device
     */
    private boolean isRequestingSensorEventData() {
        for (Map.Entry<String, SensorDataRequest> sensorDataRequestEntry : sensorDataRequests.entrySet()) {
            if (sensorDataRequestEntry.getValue().getEndTimestamp() == DataRequest.TIMESTAMP_NOT_SET) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the app is requesting sensor data from
     * the device with the specified node id
     */
    private boolean isRequestingSensorEventData(String nodeId) {
        SensorDataRequest request = sensorDataRequests.get(nodeId);
        if (request == null) {
            return false;
        }
        return request.getEndTimestamp() == DataRequest.TIMESTAMP_NOT_SET;
    }

    /**
     * Returns true if the app is requesting data by the specified sensor
     * from the device with the specified node id
     */
    private boolean isRequestingSensorEventData(String nodeId, String sensorName) {
        // check if the request has reached is end timestamp
        if (!isRequestingSensorEventData(nodeId)) {
            return false;
        }

        // check if the current sensor is selected
        boolean sensorIsRequested = false;
        for (DeviceSensor deviceSensor : selectedSensors.get(nodeId)) {
            if (!deviceSensor.getName().equals(sensorName)) {
                continue;
            }
            sensorIsRequested = true;
        }
        return sensorIsRequested;
    }

    /**
     * Sends all available sensor data requests to the assigned nodes
     */
    private void sendSensorEventDataRequests() {
        try {
            Log.v(TAG, "Updating sensor event data request");
            for (Map.Entry<String, SensorDataRequest> sensorDataRequestEntry : sensorDataRequests.entrySet()) {
                sendSensorEventDataRequest(sensorDataRequestEntry.getKey(), sensorDataRequestEntry.getValue());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }


    private void sendSensorEventDataRequest(String nodeId, SensorDataRequest request) {
        try {
            StringBuilder sb = new StringBuilder("Sending sensor data request to " + nodeId);
            for (Integer sensorType : request.getSensorTypes()) {
                sb.append("\n - " + String.valueOf(sensorType));
            }
            Log.d(TAG, sb.toString());
            app.getGoogleApiMessenger().sendMessageToNode(MessageHandler.PATH_SENSOR_DATA_REQUEST, request.toJson(), nodeId);
        } catch (Exception ex) {
            Log.w(TAG, "Unable to send sensor data request: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Sets the end timestamps of all available sensor data requests to now
     * and sends them to the assigned nodes
     */
    private void stopRequestingSensorEventData() {
        if (!isRequestingSensorEventData()) {
            return;
        }
        try {
            Log.v(TAG, "Stopping to request sensor event data");
            for (Map.Entry<String, SensorDataRequest> sensorDataRequestEntry : sensorDataRequests.entrySet()) {
                sensorDataRequestEntry.getValue().setEndTimestamp(System.currentTimeMillis());
            }
            sendSensorEventDataRequests();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Creates or updates a visualization card and notifies the @cardListAdapter
     * in order to update the @ChartView with the provided @DataBatch
     */
    private void renderDataBatch(DataBatch dataBatch, String sourceNodeId) {
        try {
            // Don't render if the data isn't requested anymore.
            // This can happen if the request has been updated but data
            // has already been sent by the request receiver
            if (!isRequestingSensorEventData(sourceNodeId, dataBatch.getSource())) {
                return;
            }

            // get the visualization card
            String key = VisualizationCardData.generateKey(sourceNodeId, dataBatch.getSource());
            VisualizationCardData visualizationCardData = cardListAdapter.getVisualizationCard(key);

            // create a new card if not yet avaialable
            if (visualizationCardData == null) {
                Node sourceNode = app.getGoogleApiMessenger().getLastConnectedNodeById(sourceNodeId);
                if (sourceNode == null) {
                    throw new Exception("Unknown source node");
                }
                visualizationCardData = new VisualizationCardData(key);
                visualizationCardData.setHeading(dataBatch.getSource());
                visualizationCardData.setSubHeading(sourceNode.getDisplayName());
                cardListAdapter.add(visualizationCardData);
                cardListAdapter.notifyDataSetChanged();
            }

            // update the card data
            DataBatch visualizationDataBatch = visualizationCardData.getDataBatch();
            if (visualizationDataBatch == null) {
                visualizationDataBatch = dataBatch;
                visualizationDataBatch.setCapacity(DataBatch.CAPACITY_UNLIMITED);
                visualizationCardData.setDataBatch(visualizationDataBatch);
            } else {
                visualizationDataBatch.addData(dataBatch.getDataList());
            }

            cardListAdapter.invalidateVisualization(visualizationCardData.getKey());
        } catch (Exception ex) {
            Log.w(TAG, "Unable to render data batch: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Removes all visualizations from the card list adapter that are currently
     * not requested
     */
    private void removeUnneededVisualizationCards() {
        Map<String, VisualizationCardData> removableVisualizationCards = new HashMap<>();

        Map<String, VisualizationCardData> visualizationCards = cardListAdapter.getVisualizationCards();
        for (Map.Entry<String, VisualizationCardData> visualizationCardDataEntry : visualizationCards.entrySet()) {
            String nodeId = visualizationCardDataEntry.getKey();
            VisualizationCardData visualizationCard = visualizationCardDataEntry.getValue();

            // check if the data that the current card holds should be rendered
            if (!isRequestingSensorEventData(nodeId, visualizationCard.getDataBatch().getSource())) {
                removableVisualizationCards.put(nodeId, visualizationCard);
                continue;
            }
        }

        for (Map.Entry<String, VisualizationCardData> visualizationCardDataEntry : removableVisualizationCards.entrySet()) {
            Log.d(TAG, "Removing unneeded visualization card: " + visualizationCardDataEntry.getValue().getHeading());
            cardListAdapter.remove(visualizationCardDataEntry.getValue());
        }
    }

}
