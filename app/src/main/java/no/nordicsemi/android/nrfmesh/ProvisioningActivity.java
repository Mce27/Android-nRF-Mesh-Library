/*
 * Copyright (c) 2018, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.nrfmesh;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.util.Base64;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.mesh.ApplicationKey;
import no.nordicsemi.android.mesh.MeshNetwork;
import no.nordicsemi.android.mesh.Provisioner;
import no.nordicsemi.android.mesh.provisionerstates.ProvisioningCapabilities;
import no.nordicsemi.android.mesh.provisionerstates.ProvisioningFailedState;
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode;
import no.nordicsemi.android.mesh.utils.AuthenticationOOBMethods;
import no.nordicsemi.android.mesh.utils.InputOOBAction;
import no.nordicsemi.android.mesh.utils.MeshParserUtils;
import no.nordicsemi.android.mesh.utils.OutputOOBAction;
import no.nordicsemi.android.mesh.utils.StaticOOBType;
import no.nordicsemi.android.nrfmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.nrfmesh.adapter.ProvisioningProgressAdapter;
import no.nordicsemi.android.nrfmesh.databinding.ActivityMeshProvisionerBinding;
import no.nordicsemi.android.nrfmesh.dialog.DialogFragmentAuthenticationInput;
import no.nordicsemi.android.nrfmesh.dialog.DialogFragmentConfigurationComplete;
import no.nordicsemi.android.nrfmesh.DialogFragmentOobPublicKey;
import no.nordicsemi.android.nrfmesh.dialog.DialogFragmentProvisioningFailedError;
import no.nordicsemi.android.nrfmesh.dialog.DialogFragmentSelectOOBType;
import no.nordicsemi.android.nrfmesh.dialog.DialogFragmentUnicastAddress;
import no.nordicsemi.android.nrfmesh.keys.AppKeysActivity;
import no.nordicsemi.android.nrfmesh.node.dialog.DialogFragmentNodeName;
import no.nordicsemi.android.nrfmesh.utils.ProvisionerStates;
import no.nordicsemi.android.nrfmesh.utils.Utils;
import no.nordicsemi.android.nrfmesh.viewmodels.ProvisionerProgress;
import no.nordicsemi.android.nrfmesh.viewmodels.ProvisioningViewModel;

import static no.nordicsemi.android.nrfmesh.utils.Utils.RESULT_KEY;

@AndroidEntryPoint
public class ProvisioningActivity extends AppCompatActivity implements
        DialogFragmentOobPublicKey.DialogFragmentOobPublicKeysListener,
        DialogFragmentSelectOOBType.DialogFragmentSelectOOBTypeListener,
        DialogFragmentAuthenticationInput.ProvisionerInputFragmentListener,
        DialogFragmentNodeName.DialogFragmentNodeNameListener,
        DialogFragmentUnicastAddress.DialogFragmentUnicastAddressListener,
        DialogFragmentProvisioningFailedError.DialogFragmentProvisioningFailedErrorListener,
        DialogFragmentConfigurationComplete.ConfigurationCompleteListener {

    private static final String DIALOG_FRAGMENT_PROVISIONING_FAILED = "DIALOG_FRAGMENT_PROVISIONING_FAILED";
    private static final String DIALOG_FRAGMENT_AUTH_INPUT_TAG = "DIALOG_FRAGMENT_AUTH_INPUT_TAG";
    private static final String DIALOG_FRAGMENT_CONFIGURATION_STATUS = "DIALOG_FRAGMENT_CONFIGURATION_STATUS";
    private static final String DIALOG_FRAGMENT_OOB_PUBLIC_KEY_TAG = "DIALOG_FRAGMENT_OOB_PUBLIC_KEY_TAG";

    private ActivityMeshProvisionerBinding binding;
    private ProvisioningViewModel mViewModel;
    
    // NFC related fields
    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private boolean nfcScanningEnabled = false;

    private final ActivityResultLauncher<Intent> appKeySelector = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            final ApplicationKey appKey = result.getData().getParcelableExtra(RESULT_KEY);
            if (appKey != null) {
                mViewModel.getNetworkLiveData().setSelectedAppKey(appKey);
            }
        }
    });

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMeshProvisionerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mViewModel = new ViewModelProvider(this).get(ProvisioningViewModel.class);

        final Intent intent = getIntent();
        final ExtendedBluetoothDevice device = intent.getParcelableExtra(Utils.EXTRA_DEVICE);
        if (device == null)
            finish();
        final String deviceName = device != null ? device.getName() : getString(R.string.unknown_device);
        final String deviceAddress = device != null ? device.getName() : getString(R.string.unicast_address);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(deviceName);
            getSupportActionBar().setSubtitle(deviceAddress);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            nfcPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 
                PendingIntent.FLAG_MUTABLE);
        }

        if (savedInstanceState == null && device != null)
            mViewModel.connect(this, device, false);

        binding.containerName.image
                .setBackground(ContextCompat.getDrawable(this, R.drawable.ic_label_outline));
        binding.containerName.title.setText(R.string.summary_name);
        binding.containerName.text.setVisibility(View.VISIBLE);
        binding.containerName.getRoot().setOnClickListener(v -> {
            final DialogFragmentNodeName dialogFragmentNodeName = DialogFragmentNodeName.newInstance(deviceName);
            dialogFragmentNodeName.show(getSupportFragmentManager(), null);
        });

        binding.containerUnicast.image
                .setBackground(ContextCompat.getDrawable(this, R.drawable.ic_lan_24dp));
        binding.containerUnicast.title.setText(R.string.title_unicast_address);
        binding.containerUnicast.text.setVisibility(View.VISIBLE);
        binding.containerUnicast.getRoot().setOnClickListener(v -> {
            final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
            if (node != null && node.getProvisioningCapabilities() != null) {
                final int elementCount = node.getProvisioningCapabilities().getNumberOfElements();
                final DialogFragmentUnicastAddress dialogFragmentFlags = DialogFragmentUnicastAddress.
                        newInstance(mViewModel.getNetworkLiveData().getMeshNetwork().getUnicastAddress(), elementCount);
                dialogFragmentFlags.show(getSupportFragmentManager(), null);
            }
        });

        binding.containerAppKeys.image
                .setBackground(ContextCompat.getDrawable(this, R.drawable.ic_vpn_key_24dp));
        binding.containerAppKeys.title.setText(R.string.title_app_keys);
        binding.containerAppKeys.text.setVisibility(View.VISIBLE);
        binding.containerAppKeys.getRoot().setOnClickListener(v -> {
            final Intent manageAppKeys = new Intent(ProvisioningActivity.this, AppKeysActivity.class);
            manageAppKeys.putExtra(Utils.EXTRA_DATA, Utils.ADD_APP_KEY);
            appKeySelector.launch(manageAppKeys);
        });

        mViewModel.getConnectionState().observe(this, binding.connectionState::setText);

        mViewModel.isConnected().observe(this, connected -> {
            final boolean isComplete = mViewModel.isProvisioningComplete();
            if (isComplete) {
                return;
            }

            if (connected != null && !connected)
                finish();
        });

        mViewModel.isDeviceReady().observe(this, deviceReady -> {
            if (mViewModel.getBleMeshManager().isDeviceReady()) {
                binding.connectivityProgressContainer.setVisibility(View.GONE);
                final boolean isComplete = mViewModel.isProvisioningComplete();
                if (isComplete) {
                    binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                    binding.infoProvisioningStatusContainer.getRoot().setVisibility(View.VISIBLE);
                    setupProvisionerStateObservers();
                    return;
                }
                binding.dataContainer.setVisibility(View.VISIBLE);
            }
        });

        mViewModel.isReconnecting().observe(this, isReconnecting -> {
            if (isReconnecting != null && isReconnecting) {
                mViewModel.getUnprovisionedMeshNode().removeObservers(this);
                binding.infoProvisioningStatusContainer.getRoot().setVisibility(View.GONE);
                binding.dataContainer.setVisibility(View.GONE);
                binding.provisioningProgressBar.setVisibility(View.GONE);
                binding.connectivityProgressContainer.setVisibility(View.VISIBLE);
            } else {
                setResultIntent();
            }
        });

        mViewModel.getNetworkLiveData().observe(this, meshNetworkLiveData -> {
            binding.containerName.text.setText(meshNetworkLiveData.getNodeName());
            final ApplicationKey applicationKey = meshNetworkLiveData.getSelectedAppKey();
            if (applicationKey != null) {
                binding.containerAppKeys.text.setText(MeshParserUtils.bytesToHex(applicationKey.getKey(), false));
            } else {
                binding.containerAppKeys.text.setText(getString(R.string.no_app_keys));
            }
            binding.containerUnicast.text.setText(getString(R.string.hex_format,
                    String.format(Locale.US, "%04X", meshNetworkLiveData.getMeshNetwork().getUnicastAddress())));
        });

        mViewModel.getUnprovisionedMeshNode().observe(this, meshNode -> {
            if (meshNode != null) {
                final ProvisioningCapabilities capabilities = meshNode.getProvisioningCapabilities();
                if (capabilities != null) {
                    binding.provisioningProgressBar.setVisibility(View.INVISIBLE);
                    binding.actionProvisionDevice.setText(R.string.provision_action);
                    binding.containerUnicast.getRoot().setVisibility(View.VISIBLE);
                    final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                    if (network != null) {
                        try {
                            final int elementCount = capabilities.getNumberOfElements();
                            final Provisioner provisioner = network.getSelectedProvisioner();
                            final int unicast = network.nextAvailableUnicastAddress(elementCount, provisioner);
                            network.assignUnicastAddress(unicast);
                            updateCapabilitiesUi(capabilities);
                        } catch (IllegalArgumentException ex) {
                            binding.actionProvisionDevice.setEnabled(false);
                            mViewModel.displaySnackBar(this, binding.coordinator, ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage(), Snackbar.LENGTH_LONG);
                        }
                    }
                }
            }
        });

        binding.actionProvisionDevice.setOnClickListener(v -> {
            final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
            if (node == null) {
                device.setName(mViewModel.getNetworkLiveData().getNodeName());
                mViewModel.getNrfMeshRepository().identifyNode(device);
                return;
            }

            if (node.getProvisioningCapabilities() != null) {
                if (node.getProvisioningCapabilities().isPublicKeyOobSupported()) {
                    DialogFragmentOobPublicKey.newInstance().show(getSupportFragmentManager(), DIALOG_FRAGMENT_OOB_PUBLIC_KEY_TAG);
                } else {
                    if (node.getProvisioningCapabilities().getAvailableOOBTypes().size() == 1 &&
                            node.getProvisioningCapabilities().getAvailableOOBTypes().get(0) == AuthenticationOOBMethods.NO_OOB_AUTHENTICATION) {
                        onNoOOBSelected();
                    } else {
                        final DialogFragmentSelectOOBType fragmentSelectOOBType = DialogFragmentSelectOOBType.newInstance(node.getProvisioningCapabilities());
                        fragmentSelectOOBType.show(getSupportFragmentManager(), null);
                    }
                }
            }
        });

        if (savedInstanceState == null)
            mViewModel.getNetworkLiveData().resetSelectedAppKey();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()) ||
            NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())) {
            
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                byte[] publicKey = readPublicKeyFromNfc(tag);
                if (publicKey != null) {
                    // Check if we have an active public key dialog
                    DialogFragmentOobPublicKey dialog = (DialogFragmentOobPublicKey) 
                        getSupportFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_OOB_PUBLIC_KEY_TAG);
                    if (dialog != null) {
                        // Auto-fill the dialog if it's open
                        dialog.setPublicKey(MeshParserUtils.bytesToHex(publicKey, false));
                        Toast.makeText(this, R.string.nfc_key_received, Toast.LENGTH_SHORT).show();
                        disableNfcForegroundDispatch();
                    } else {
                        // Store the key for later use (when dialog opens)
                        mViewModel.setScannedOobPublicKey(publicKey);
                        Toast.makeText(this, R.string.nfc_key_received, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, R.string.nfc_error_read_failed, Toast.LENGTH_SHORT).show();
                }
            }
        } else if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            final Uri uri = intent.getData();
            if (uri != null && "mesh".equals(uri.getScheme()) && "publickey".equals(uri.getHost())) {
                String base64Key = uri.getPath();
                if (base64Key != null) {
                    if (base64Key.startsWith("/")) {
                        base64Key = base64Key.substring(1);
                    }
                    try {
                        // Decode the Base64 public key
                        final byte[] provisioneePublicKey = Base64.decode(base64Key, Base64.DEFAULT);
                        // Pass the key to the ViewModel
                        mViewModel.setScannedOobPublicKey(provisioneePublicKey);
                        // Inform the user that the key was received successfully
                        Toast.makeText(this, "Public key received via NFC.", Toast.LENGTH_SHORT).show();
                    } catch (IllegalArgumentException e) {
                        // Handle error: Base64 decoding failed
                        Toast.makeText(this, "Error: Invalid public key format.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }

    @Override
    public void onNfcScanRequested() {
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            enableNfcForegroundDispatch();
            Toast.makeText(this, R.string.nfc_scan_prompt, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "NFC is not available or disabled", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @Nullable
    public byte[] getScannedOobPublicKey() {
        return mViewModel.getNrfMeshRepository().getOobPublicKey();
    }

    private void enableNfcForegroundDispatch() {
        if (nfcAdapter != null) {
            IntentFilter[] intentFilters = new IntentFilter[]{
                new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
                new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
            };
            nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, intentFilters, null);
            nfcScanningEnabled = true;
        }
    }

    private void disableNfcForegroundDispatch() {
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
            nfcScanningEnabled = false;
        }
    }

    private byte[] readPublicKeyFromNfc(Tag tag) {
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                NdefMessage message = ndef.getNdefMessage();
                if (message != null) {
                    NdefRecord[] records = message.getRecords();
                    for (NdefRecord record : records) {
                        // Check if this is a URI record
                        if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN && 
                            java.util.Arrays.equals(record.getType(), NdefRecord.RTD_URI)) {
                            // Parse URI record
                            byte[] payload = record.getPayload();
                            if (payload.length > 1) {
                                // Skip URI identifier code (first byte)
                                String uri = new String(payload, 1, payload.length - 1);
                                if (uri.startsWith("mesh://publickey/")) {
                                    String base64Key = uri.substring("mesh://publickey/".length());
                                    try {
                                        // Decode the Base64 public key
                                        return Base64.decode(base64Key, Base64.DEFAULT);
                                    } catch (IllegalArgumentException e) {
                                        // Invalid Base64
                                    }
                                }
                            }
                        }
                    }
                }
                ndef.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        disableNfcForegroundDispatch();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        //We disconnect from the device if the user presses the back button
        disconnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onPinInputComplete(final String pin) {
        mViewModel.getMeshManagerApi().setProvisioningAuthentication(pin);
    }

    @Override
    public void onPinInputCanceled() {
        final String message = getString(R.string.provisioning_cancelled);
        final Snackbar snackbar = Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG);
        snackbar.show();
        disconnect();
    }

    @Override
    public boolean onNodeNameUpdated(@NonNull final String nodeName) {
        mViewModel.getNetworkLiveData().setNodeName(nodeName);
        return true;
    }

    @Override
    public boolean setUnicastAddress(final int unicastAddress) {
        final MeshNetwork network = mViewModel.getMeshManagerApi().getMeshNetwork();
        if (network != null) {
            return network.assignUnicastAddress(unicastAddress);
        }
        return false;
    }

    @Override
    public int getNextUnicastAddress(final int elementCount) {
        final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
        return network.nextAvailableUnicastAddress(elementCount, network.getSelectedProvisioner());
    }

    @Override
    public void onProvisioningFailed() {
        //Provisioning failed so now we go back to the scanner page.
        disconnect();
        setResultIntent();
    }

    private void disconnect() {
        mViewModel.getUnprovisionedMeshNode().removeObservers(this);
        mViewModel.disconnect();
    }

    public void setupProvisionerStateObservers() {
        binding.infoProvisioningStatusContainer.getRoot().setVisibility(View.VISIBLE);

        final RecyclerView recyclerView = binding.infoProvisioningStatusContainer.recyclerViewProvisioningProgress;
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        final ProvisioningProgressAdapter adapter = new ProvisioningProgressAdapter(mViewModel.getProvisioningStatus());
        recyclerView.setAdapter(adapter);

        mViewModel.getProvisioningStatus().observe(this, provisioningStateLiveData -> {
            if (provisioningStateLiveData != null) {
                final ProvisionerProgress provisionerProgress = provisioningStateLiveData.getProvisionerProgress();
                adapter.refresh(provisioningStateLiveData.getStateList());
                if (provisionerProgress != null) {
                    final ProvisionerStates state = provisionerProgress.getState();
                    switch (state) {
                        case PROVISIONING_FAILED:
                            if (getSupportFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_PROVISIONING_FAILED) == null) {
                                final String statusMessage = ProvisioningFailedState.parseProvisioningFailure(this, provisionerProgress.getStatusReceived());
                                DialogFragmentProvisioningFailedError message = DialogFragmentProvisioningFailedError.newInstance(getString(R.string.title_error_provisioning_failed), statusMessage);
                                message.show(getSupportFragmentManager(), DIALOG_FRAGMENT_PROVISIONING_FAILED);
                            }
                            break;
                        case PROVISIONING_AUTHENTICATION_STATIC_OOB_WAITING:
                        case PROVISIONING_AUTHENTICATION_OUTPUT_OOB_WAITING:
                        case PROVISIONING_AUTHENTICATION_INPUT_OOB_WAITING:
                            if (getSupportFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_AUTH_INPUT_TAG) == null) {
                                DialogFragmentAuthenticationInput dialogFragmentAuthenticationInput = DialogFragmentAuthenticationInput.
                                        newInstance(mViewModel.getUnprovisionedMeshNode().getValue());
                                dialogFragmentAuthenticationInput.show(getSupportFragmentManager(), DIALOG_FRAGMENT_AUTH_INPUT_TAG);
                            }
                            break;
                        case PROVISIONING_AUTHENTICATION_INPUT_ENTERED:
                            final DialogFragmentAuthenticationInput fragment = (DialogFragmentAuthenticationInput) getSupportFragmentManager().
                                    findFragmentByTag(DIALOG_FRAGMENT_AUTH_INPUT_TAG);
                            if (fragment != null)
                                fragment.dismiss();
                            break;
                        case APP_KEY_STATUS_RECEIVED:
                            if (getSupportFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_CONFIGURATION_STATUS) == null) {
                                DialogFragmentConfigurationComplete fragmentConfigComplete = DialogFragmentConfigurationComplete.
                                        newInstance(getString(R.string.title_configuration_compete), getString(R.string.configuration_complete_summary));
                                fragmentConfigComplete.show(getSupportFragmentManager(), DIALOG_FRAGMENT_CONFIGURATION_STATUS);
                            }
                            break;
                        case PROVISIONER_UNASSIGNED:
                            setResultIntent();
                            break;
                        default:
                            break;
                    }

                }
                binding.dataContainer.setVisibility(View.GONE);
            }
        });

    }

    @Override
    public void onConfigurationCompleted() {
        setResultIntent();
    }

    private void setResultIntent() {
        final Intent returnIntent = new Intent();
        if (mViewModel.isProvisioningComplete()) {
            returnIntent.putExtra(Utils.PROVISIONING_COMPLETED, true);
            setResult(Activity.RESULT_OK, returnIntent);
            final ProvisionerProgress progress = mViewModel.getProvisioningStatus().getProvisionerProgress();
            if (progress.getState() == ProvisionerStates.PROVISIONER_UNASSIGNED) {
                returnIntent.putExtra(Utils.PROVISIONER_UNASSIGNED, true);
            } else {
                if (mViewModel.isCompositionDataStatusReceived()) {
                    returnIntent.putExtra(Utils.COMPOSITION_DATA_COMPLETED, true);
                    if (mViewModel.isDefaultTtlReceived()) {
                        returnIntent.putExtra(Utils.DEFAULT_GET_COMPLETED, true);
                        if (mViewModel.isNetworkRetransmitSetCompleted()) {
                            returnIntent.putExtra(Utils.NETWORK_TRANSMIT_SET_COMPLETED, true);
                            if (mViewModel.getNetworkLiveData().getMeshNetwork().getAppKeys().isEmpty() || mViewModel.isAppKeyAddCompleted()) {
                                returnIntent.putExtra(Utils.APP_KEY_ADD_COMPLETED, true);
                            }
                        }
                    }
                }
            }
        }
        finish();
    }

    private void updateCapabilitiesUi(final ProvisioningCapabilities capabilities) {
        binding.capabilitiesContainer.getRoot().setVisibility(View.VISIBLE);

        final String numberOfElements = String.valueOf(capabilities.getNumberOfElements());
        binding.capabilitiesContainer.containerElementCount.text.setText(numberOfElements);
        binding.capabilitiesContainer.containerSupportedAlgorithm.text.setText(mViewModel.parseAlgorithms(capabilities));
        binding.capabilitiesContainer.containerPublicKeyType.text.
                setText(capabilities.isPublicKeyInformationAvailable() ?
                        R.string.public_key_information_available : R.string.public_key_information_unavailable);
        binding.capabilitiesContainer.containerStaticOobType.text.
                setText(capabilities.isStaticOOBInformationAvailable() ?
                        R.string.static_oob_information_available : R.string.static_oob_information_unavailable);
        binding.capabilitiesContainer.containerOutputOobSize.text.setText(String.valueOf(capabilities.getOutputOOBSize()));
        binding.capabilitiesContainer.containerOutputActions.text.setText(mViewModel.parseOutputOOBActions(this, capabilities));
        binding.capabilitiesContainer.containerInputOobSize.text.setText(String.valueOf(capabilities.getInputOOBSize()));
        binding.capabilitiesContainer.containerInputActions.text.setText(mViewModel.parseInputOOBActions(this, capabilities));
    }

    @Override
    public void onPublicKeyAdded(@Nullable final byte[] publicKey) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            node.setProvisioneePublicKeyXY(publicKey);
            if (node.getProvisioningCapabilities().getAvailableOOBTypes().size() == 1 &&
                    node.getProvisioningCapabilities().getAvailableOOBTypes().get(0) == AuthenticationOOBMethods.NO_OOB_AUTHENTICATION) {
                onNoOOBSelected();
            } else {
                final DialogFragmentSelectOOBType fragmentSelectOOBType = DialogFragmentSelectOOBType.newInstance(node.getProvisioningCapabilities());
                fragmentSelectOOBType.show(getSupportFragmentManager(), null);
            }
        }
    }

    @Override
    public void onNoOOBSelected() {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());
                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);

                byte[] oobKey = mViewModel.getNrfMeshRepository().getOobPublicKey();
                if (oobKey != null) {
                    node.setProvisioneePublicKeyXY(oobKey);
                    // Clear the key so it is only used for this provisioning attempt.
                    mViewModel.getNrfMeshRepository().clearOobPublicKey();
                }
                mViewModel.getMeshManagerApi().startProvisioning(node);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator, ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage(), Snackbar.LENGTH_LONG);
            }
        }
    }

    @Override
    public void onStaticOOBSelected(final StaticOOBType staticOOBType) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());
                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                mViewModel.getMeshManagerApi().startProvisioningWithStaticOOB(node);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator, ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage(), Snackbar.LENGTH_LONG);
            }
        }
    }

    @Override
    public void onOutputOOBActionSelected(final OutputOOBAction action) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());
                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                mViewModel.getMeshManagerApi().startProvisioningWithOutputOOB(node, action);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator, ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage(), Snackbar.LENGTH_LONG);
            }
        }
    }

    @Override
    public void onInputOOBActionSelected(final InputOOBAction action) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());
                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                mViewModel.getMeshManagerApi().startProvisioningWithInputOOB(node, action);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator, ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage(), Snackbar.LENGTH_LONG);
            }
        }
    }
}
