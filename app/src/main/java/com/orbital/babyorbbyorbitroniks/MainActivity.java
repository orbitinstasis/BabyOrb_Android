/**
 * TODO: display peak in the layout
 */
package com.orbital.babyorbbyorbitroniks;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends Activity {

    /**
     * CONSTANTS
     */
    private static final int REQUEST_ENABLE_BT = 1; // used for the intent driven bt check - this is faded out as it keeps closing my activity
    private static final int MODE = 0;
    private static final int RED_VAL = 1;
    private static final int GREEN_VAL = 2;
    private static final int BLUE_VAL = 3;
    private static final int MOTOR_VAL = 4;
    private static final int HW_LOOP_DELAY_TIME = 5;
    private static final int PEAK_VAL = 6;
    private static final int SINGLE_PEAK_WIDTH = 28; // width of a '>' character

    /**
     * VARIABLES
     */

    ArrayAdapter <CharSequence> adapter;
    Spinner spinner;
    SeekBar seekBar_Red, seekBar_Green, seekBar_Blue, seekBar_Grey, seekBar_Speed;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothDevice mmDevice;
    BluetoothSocket mmSocket;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    EditText txText;
    TextView rxView, peakView;
    Button sendTxBtn, connectBtn, redIOBtn, greenIOBtn, blueIOBtn;
    RadioGroup radioGroup_Mode_select;
    RadioButton[] radioBtn_mode = new RadioButton[4];
    Thread bytesProducerThread;
    Thread integerProducerThread;
    Thread consumerThread;
    String integerValue = "";
    Boolean stopWorker = false;
    BlockingQueue<Character> queueRaw = new ArrayBlockingQueue<Character>(512);
    BlockingQueue<Integer> queueInteger = new ArrayBlockingQueue<Integer>(512);
    int[] state = new int[7];
    int timeToSleep = 100;
    int peakViewMaxWidth = 0;
    int maxPeakViews = 0;
    Handler modeFetchHandler = new Handler();
    Handler peakViewHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
//        if(!mBluetoothAdapter.isEnabled()) {
////            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
////            startActivityForResult(enableBluetooth, REQUEST_ENABLE_BT);
//            mBluetoothAdapter.enable();
//        }
        super.onCreate(savedInstanceState);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        setContentView(R.layout.activity_main);
        initializeVariables();

        radioGroupModeListenerDecl();
        workerThreadDecl();
        seekBarListeners();
    }

    /**
     * Stops all worker threads, kills and closes the bluetooth connection and streams
     */
    @Override
    public void onDestroy()
    {
        super.onDestroy();
        stopWorker = true;
        mBluetoothAdapter.disable();
        try {
            mmInputStream.close();
            mmOutputStream.close();
            mmSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * FUNCTIONS
     */

    /**
     * Initialises all used variables
     */
    private void initializeVariables() {
        peakView = (TextView) findViewById(R.id.peak_textView);
        spinner = (Spinner) findViewById(R.id.spinner);
        adapter = ArrayAdapter.createFromResource(getApplicationContext(), R.array.sleep_timeout, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                String timeout = (String)adapterView.getItemAtPosition(i);
                timeout = timeout.substring(0,2).trim();
                timeToSleep = Integer.parseInt(timeout) * 60;
                if (state[MODE] > 2)
                    Toast.makeText(getBaseContext(), timeToSleep + " Seconds until sleep", Toast.LENGTH_LONG).show();
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });
        radioGroup_Mode_select = (RadioGroup) findViewById(R.id.radioGroup_mode_select);
        seekBar_Speed = (SeekBar) findViewById(R.id.seekBarSpeed);
        seekBar_Grey = (SeekBar) findViewById(R.id.seekBarMotorGrey);
        seekBar_Red = (SeekBar) findViewById(R.id.seekBarRed);
        seekBar_Green = (SeekBar) findViewById(R.id.seekBarGreen);
        seekBar_Blue = (SeekBar) findViewById(R.id.seekBarBlue);
        rxView = (TextView) findViewById(R.id.rxView);
        txText = (EditText) findViewById(R.id.txTextBox);
        sendTxBtn = (Button) findViewById(R.id.txSendBtn);
        connectBtn = (Button) findViewById(R.id.connectBT);
        redIOBtn = (Button) findViewById(R.id.redOffBtn);
        greenIOBtn = (Button) findViewById(R.id.greenOffBtn);
        blueIOBtn = (Button) findViewById(R.id.blueOffBtn);
        seekBar_Red.setEnabled(false);
        seekBar_Green.setEnabled(false);
        seekBar_Blue.setEnabled(false);
        seekBar_Grey.setEnabled(false);
        seekBar_Speed.setEnabled(true);
        txText.setEnabled(false);
        sendTxBtn.setEnabled(false);
        for(int i = 0; i < radioGroup_Mode_select.getChildCount(); i++){
            (radioGroup_Mode_select.getChildAt(i)).setEnabled(false);
        }
        radioBtn_mode[0] = (RadioButton) findViewById(R.id.radioButton_Mode_1);
        radioBtn_mode[1] = (RadioButton) findViewById(R.id.radioButton_Mode_2);
        radioBtn_mode[2] = (RadioButton) findViewById(R.id.radioButton_Mode_3);
        radioBtn_mode[3] = (RadioButton) findViewById(R.id.radioButton_Mode_4);
        peakView.post(new Runnable()
        {
            @Override
            public void run()
            {
                peakViewMaxWidth = peakView.getWidth();
                maxPeakViews = (int)Math.floor((peakViewMaxWidth / SINGLE_PEAK_WIDTH));
            }
        });
    }

    /**
     * Sets the debug textView text with the input param
     * @param input
     */
    void setText (String input) {
        rxView.setText(input);
    }

    /**
     * assigns state i value with value if the existing value is different
     * @param value
     * @param i
     */
    private void assignVal(int value, int i)
    {
        if (value != state[i])
            state[i] = value;
    }

    /**
     * Correct LED IO
     * @param v
     */
    public void ledIoFromBtnController(View v)
    {
        String tag = v.getTag().toString();
        String tx = "";
        int led = 0;
        switch (tag)
        {
            case "redBtn":
                led = RED_VAL;
                tx += "lr";
                break;
            case "greenBtn":
                led = GREEN_VAL;
                tx += "lg";
                break;
            case "blueBtn":
                led = BLUE_VAL;
                tx += "lb";
                break;
        }
        if (state[led] == 0) // if the led is off then turn it on
            tx += 10;
        else // led is on so turn it off
            tx += 0;
        txSend(tx);
    }

    /**
     * Sets correct visibility for the buttons that handle the Led IO depending on the mode
     */
    private void setoLedIoBtnVisibility()
    {
        int i = radioGroup_Mode_select.indexOfChild(findViewById(radioGroup_Mode_select.getCheckedRadioButtonId()));
        if (i == 1)
        {
            redIOBtn.setVisibility(View.INVISIBLE);
            greenIOBtn.setVisibility(View.INVISIBLE);
            blueIOBtn.setVisibility(View.INVISIBLE);
        }
        else
        {
            redIOBtn.setVisibility(View.VISIBLE);
            greenIOBtn.setVisibility(View.VISIBLE);
            blueIOBtn.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Handles the seekbars enabled values depending on the current mode
     */
    private void handleSeekbarEn()
    {
        int s = radioGroup_Mode_select.indexOfChild(findViewById(radioGroup_Mode_select.getCheckedRadioButtonId()));
        switch(s)
        {
            case 1:
                seekBar_Red.setEnabled(true);
                seekBar_Green.setEnabled(true);
                seekBar_Blue.setEnabled(true);
                seekBar_Grey.setEnabled(true);
                seekBar_Speed.setVisibility(View.INVISIBLE);
                break;
            case 2:
                seekBar_Red.setEnabled(false);
                seekBar_Green.setEnabled(false);
                seekBar_Blue.setEnabled(false);
                seekBar_Grey.setEnabled(true);
                seekBar_Speed.setVisibility(View.VISIBLE);
                break;
            case 3:
            case 4:
                seekBar_Red.setEnabled(false);
                seekBar_Green.setEnabled(false);
                seekBar_Blue.setEnabled(false);
                seekBar_Grey.setEnabled(false);
                seekBar_Speed.setVisibility(View.INVISIBLE);
                break;
        }

    }

    /**
     * Starts the aysnc task responsible for connecting to the bt hw
     * @param v
     */
    public void onConnectBtn(View v) {
        new connectToBt().execute();
    }

    /**
     * Fetch the data in the textbox, parse it and append the command delimiter to it
     * @param v
     */
    public void onTxSendBtn(View v)
    {
        txText = (EditText) findViewById(R.id.txTextBox);
        String msg = null;
        try {msg = txText.getText().toString();} catch (NullPointerException e){e.printStackTrace();}
        if (msg.isEmpty()) return;
        txText.setText("");
        Toast.makeText(this, "Sent " + msg, Toast.LENGTH_LONG).show();
        txSend(msg);
    }

    /**
     * Write the parsed command via bluetooth
     * @param msg
     */
    private void txSend(String msg)
    {
        msg += ",";
        try {
            mmOutputStream.write(msg.getBytes());
            Thread.sleep(1);
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't write to buffer", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * ASYNC TASKS
     */

    /**
     * Thread to connect to the bluethooth hardware, and deal with the view accordingly
     */
    private class connectToBt extends AsyncTask {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            connectBtn.setEnabled(false);
            connectBtn.setText("Connecting");
        }

        @Override
        protected Object doInBackground(Object[] objects) {
            if(!mBluetoothAdapter.isEnabled()) {
//            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//            startActivityForResult(enableBluetooth, REQUEST_ENABLE_BT);
                mBluetoothAdapter.enable();
            }
            while (!mBluetoothAdapter.isEnabled()){try {Thread.sleep(50);} catch (InterruptedException e) {e.printStackTrace();}}
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if(pairedDevices.size() > 0)
            {
                for(BluetoothDevice device : pairedDevices)
                {
                    if(device.getName().equals("BabyOrb")) //Note, you will need to change this to match the name of your device
                    {
                        mmDevice = device;
                        break;
                    }
                }
            }
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); //Standard SerialPortService ID
            try {
                mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
                mmSocket.connect();
                mmOutputStream = mmSocket.getOutputStream();
                mmInputStream = mmSocket.getInputStream();
            } catch (IOException e) {

                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Object o) {
            super.onPostExecute(o);
            if (mmSocket.isConnected())
            {
                bytesProducerThread.start();
                integerProducerThread.start();
                consumerThread.start();
                Toast.makeText(getApplicationContext(), "Connected to BabyOrb", Toast.LENGTH_SHORT).show();
                txText.setEnabled(true);
                sendTxBtn.setEnabled(true);
                connectBtn.setVisibility(View.GONE);
                modeFetchHandler.postDelayed(modeFetcherRunnable, 500);
                peakViewHandler.postDelayed(peakViewRunnable, 550);
            }
            else
            {
                Toast.makeText(getApplicationContext(), "Cannot connect to BabyOrb", Toast.LENGTH_SHORT).show();
                connectBtn.setEnabled(true);
                connectBtn.setText("Connect to BabyOrb");
            }
        }
    }

    /**
     * Decl's for each seekbars progressChanged Listeners - posts the correct command depending on input
     */
    void seekBarListeners()
    {
        seekBar_Red.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser) {
                    try {mmOutputStream.flush();} catch (IOException e) {e.printStackTrace();}
                    String tmp = "lr" + Integer.toString(progressValue);
                    txSend(tmp);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        seekBar_Green.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser)
                {
                    try {mmOutputStream.flush();} catch (IOException e) {e.printStackTrace();}
                    String tmp = "lg" + Integer.toString(progressValue);
                    txSend(tmp);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        seekBar_Blue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser)
                {
                    try {mmOutputStream.flush();} catch (IOException e) {e.printStackTrace();}
                    String tmp = "lb" + Integer.toString(progressValue);
                    txSend(tmp);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        seekBar_Grey.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser)
                {
                    try {mmOutputStream.flush();} catch (IOException e) {e.printStackTrace();}
                    String tmp = "s" + Integer.toString(progressValue);
                    txSend(tmp);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        seekBar_Speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser)
                {
                    try {mmOutputStream.flush();} catch (IOException e) {e.printStackTrace();}
                    int logPow = (int) ((0.004 * Math.pow((seekBar_Speed.getMax() - progressValue), 2)));
                    String tmp = "t" + Integer.toString( logPow );
                    txSend(tmp);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    /**
     * Decl for the radiogroup listener that deals with the mode change
     */
    void radioGroupModeListenerDecl()
    {
        radioGroup_Mode_select.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int i) {
                int index = radioGroup_Mode_select.indexOfChild(findViewById(radioGroup_Mode_select.getCheckedRadioButtonId()));

                switch (index)
                {
                    case 1:
                        txSend("m1");
                        break;
                    case 2:
                        txSend("m2");
                        break;
                    case 3:
                        txSend("m3" + timeToSleep);
                        break;
                    case 4:
                        txSend("m4" + timeToSleep);
                        break;
                }
                setoLedIoBtnVisibility();
                handleSeekbarEn();
            }
        });
    }

    /**
     * Declares worker threads
     */
    private void workerThreadDecl()
    {
        /**
         * PRODUCER
         */
        bytesProducerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    int bytesAvailable = 0;
                    try {
                        Thread.sleep(100);
                        bytesAvailable = mmInputStream.available();
                    } catch (InterruptedException | NullPointerException | IOException e) {
                        e.printStackTrace();
                    }
                    if(bytesAvailable > 0)
                    {
                        byte[] packetBytes = new byte[bytesAvailable];
                        try {

                            mmInputStream.read(packetBytes);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        for(int i=0;i<bytesAvailable;i++)
                        {
                            try {
                                queueRaw.put((char)packetBytes[i]);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        });
        /**
         * PRODUCER 2
         */
        integerProducerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try {
                        String tmpChar = Character.toString(queueRaw.take());
                        if (tmpChar.equals(":"))
                        {
                            int intVal = 0;
                            try {intVal = Integer.parseInt(integerValue);} catch (NumberFormatException e) {e.printStackTrace();}
                            queueInteger.put(intVal);

                            integerValue = "";
                        }
                        else
                            integerValue += tmpChar;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        /**
         * CONSUMER
         */
        consumerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    int val = 0;
                    try {
                        val = queueInteger.take();
                        if (val == 255) // if we've hit a marker
                        {
                            for (int i = 0; i < 7; i++)
                            {
                                assignVal(queueInteger.take(), i);
                            }

                            if (state[MODE] != 1)
                            {
                                seekBar_Red.setProgress(state[RED_VAL]);
                                seekBar_Green.setProgress(state[GREEN_VAL]);
                                seekBar_Blue.setProgress(state[BLUE_VAL]);
                                if (state[MODE] != 2) {
                                    seekBar_Grey.setProgress(state[MOTOR_VAL]);
                                   // seekBar_Speed.setProgress(seekBar_Speed.getMax() - state[HW_LOOP_DELAY_TIME]);
                                }
                                if (state[MODE] == 4)
                                {
                                    int numOfPeaks = (int)(((state[PEAK_VAL] + 1) / (double)100)* maxPeakViews);
                                    String tmp = "";
                                    for (int i = 0; i < numOfPeaks; i++)
                                    {
                                        tmp += ">";
                                    }
                                    final String temp = tmp;
                                    peakView.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            peakView.setText(temp);
                                        }
                                    });
                                }
                            }
                        }
                    } catch (NullPointerException | InterruptedException e) {e.printStackTrace();}
                }
            }
        });
    }

    /**
     * RUNNABLES
     */

    /**
     * Deals with Led IO btns, radiobuttons and seekbars depending on the current mode
     */
    private Runnable modeFetcherRunnable = new Runnable() {
        @Override
        public void run() {
            switch (state[MODE])
            {
                case 1:
                    radioGroup_Mode_select.check(R.id.radioButton_Mode_1);
                    break;
                case 2:
                    radioGroup_Mode_select.check(R.id.radioButton_Mode_2);
                    break;
                case 3:
                    radioGroup_Mode_select.check(R.id.radioButton_Mode_3);
                    break;
                case 4:
                    radioGroup_Mode_select.check(R.id.radioButton_Mode_4);
                    break;
            }
            for(int i = 0; i < radioGroup_Mode_select.getChildCount(); i++){
                (radioGroup_Mode_select.getChildAt(i)).setEnabled(true);
            }
            handleSeekbarEn();

            if (state[MODE] == 1)
            {
                seekBar_Red.setProgress(state[RED_VAL]);
                seekBar_Blue.setProgress(state[GREEN_VAL]);
                seekBar_Green.setProgress(state[BLUE_VAL]);
            }
            if (state[MODE] <= 2)
            {
                seekBar_Grey.setProgress(state[MOTOR_VAL]);
                seekBar_Speed.setProgress(state[HW_LOOP_DELAY_TIME]);
            }

                setoLedIoBtnVisibility();
        }
    };

    /**
     * Handles the peak data - shows it in debug text view / visual output but ONLY when in mode 4
     */
    private Runnable peakViewRunnable = new Runnable() {
        @Override
        public void run() {
            if (state[MODE] == 4)
            {
                rxView.setText(String.format("%d", state[PEAK_VAL]));
            }
            peakViewHandler.post(peakViewRunnable);
        }
    };
}