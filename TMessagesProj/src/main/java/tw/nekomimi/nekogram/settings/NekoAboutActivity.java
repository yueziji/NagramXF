package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogDiagnostics;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.ProfileActivity;

import kotlin.Unit;
import tw.nekomimi.nekogram.DatacenterActivity;
import tw.nekomimi.nekogram.helpers.remote.UpdateHelper;
import tw.nekomimi.nekogram.ui.BottomBuilder;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AndroidUtil;
import xyz.nextalone.nagram.NaConfig;

public class NekoAboutActivity extends BaseNekoSettingsActivity {

    private int infoHeaderRow;
    private int versionRow;
    private int updatesRow;
    private int dialogDiagnosticsRow;
    private int toggleLogsRow;
    private int sendLogsRow;
    private int clearLogsRow;
    private int donateInfoRow;

    private int linksHeaderRow;
    private int forkChannelRow;
    private int xChannelRow;
    private int channelTipsRow;
    private int sourceCodeRow;
    private int datacenterStatusRow;
    private int linksShadowRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        infoHeaderRow = addRow();
        versionRow = addRow();
        updatesRow = addRow();
        dialogDiagnosticsRow = BuildConfig.DIALOG_DIAGNOSTICS ? addRow() : -1;
        toggleLogsRow = addRow();
        if (BuildVars.LOGS_ENABLED) {
            sendLogsRow = addRow();
            clearLogsRow = addRow();
        } else {
            sendLogsRow = -1;
            clearLogsRow = -1;
        }
        donateInfoRow = addRow();

        linksHeaderRow = addRow();
        forkChannelRow = addRow();
        xChannelRow = addRow();
        channelTipsRow = addRow();
        sourceCodeRow = addRow();
        datacenterStatusRow = addRow();
        linksShadowRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.About);
    }

    private String getUpdateChannelDetail() {
        switch (NaConfig.INSTANCE.getAutoUpdateChannel().Int()) {
            case UpdateHelper.UPDATE_OFF:
                return getString(R.string.AutoCheckUpdateOFF);
            case UpdateHelper.UPDATE_CHANNEL_RELEASE:
                return getString(R.string.AutoCheckUpdateRelease);
            case UpdateHelper.UPDATE_CHANNEL_BETA:
                return getString(R.string.AutoCheckUpdateBeta);
            default:
                return getString(R.string.AutoCheckUpdateOFF);
        }
    }

    private String getSimpleVersion() {
        String versionName = BuildConfig.VERSION_NAME.split("-")[0];
        return "Nagram XF v" + versionName;
    }

    private void showDonateDialog() {
        android.app.Activity parent = getParentActivity();
        if (parent == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(parent, resourceProvider);
        builder.setTitle(getString(R.string.Donate));
        builder.setItems(new CharSequence[]{
                getString(R.string.DonateAfdian),
                getString(R.string.DonateKoFi)
        }, (dialog, which) -> {
            if (which == 0) {
                Browser.openUrl(parent, "https://ifdian.net/a/nagramxf");
            } else if (which == 1) {
                Browser.openUrl(parent, "https://ko-fi.com/nagramxf");
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == versionRow) {
            Browser.openUrl(getParentActivity(), "https://github.com/Keeperorowner/NagramXF#readme");
        } else if (position == updatesRow) {
            showUpdatesDialog();
        } else if (position == dialogDiagnosticsRow) {
            showDialogDiagnostics();
        } else if (position == toggleLogsRow) {
            boolean wasLogsEnabled = BuildVars.LOGS_ENABLED;
            AndroidUtil.toggleLogs();
            listAdapter.notifyItemChanged(toggleLogsRow);
            if (!wasLogsEnabled && BuildVars.LOGS_ENABLED) {
                sendLogsRow = toggleLogsRow + 1;
                clearLogsRow = toggleLogsRow + 2;
                listAdapter.notifyItemInserted(sendLogsRow);
                listAdapter.notifyItemInserted(clearLogsRow);
                shiftRowsAfterLogsEnabled();
            } else if (wasLogsEnabled && !BuildVars.LOGS_ENABLED) {
                listAdapter.notifyItemRemoved(toggleLogsRow + 1);
                listAdapter.notifyItemRemoved(toggleLogsRow + 1);
                sendLogsRow = -1;
                clearLogsRow = -1;
                shiftRowsAfterLogsDisabled();
            }
        } else if (position == sendLogsRow) {
            ProfileActivity.sendLogs(getParentActivity(), false);
        } else if (position == clearLogsRow) {
            FileLog.cleanupLogs();
        } else if (position == forkChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramXF", NekoAboutActivity.this, 1);
        } else if (position == xChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramX", NekoAboutActivity.this, 1);
        } else if (position == channelTipsRow) {
            Browser.openUrl(getParentActivity(), "https://t.me/Nagram_XF_Chat");
        } else if (position == sourceCodeRow) {
            Browser.openUrl(getParentActivity(), "https://github.com/Keeperorowner/NagramXF");
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        }
    }

    private static final int EXPORT_DIALOG_DIAGNOSTICS = 7314;

    private void showDialogDiagnostics() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(getString(R.string.DialogDiagnosticsTitle));
        builder.setItems(new CharSequence[]{
                getString(DialogDiagnostics.isEnabled() ? R.string.DialogDiagnosticsPause : R.string.DialogDiagnosticsResume),
                getString(R.string.DialogDiagnosticsSnapshot),
                getString(R.string.DialogDiagnosticsProbe),
                getString(R.string.DialogDiagnosticsExport)
        }, (dialog, which) -> {
            if (which == 0) {
                DialogDiagnostics.setEnabled(!DialogDiagnostics.isEnabled());
                listAdapter.notifyItemChanged(dialogDiagnosticsRow);
            } else if (which == 3) {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, "nagram-dialog-diagnostics-" + System.currentTimeMillis() + ".jsonl");
                try {
                    startActivityForResult(intent, EXPORT_DIALOG_DIAGNOSTICS);
                } catch (Exception ignored) {
                    diagnosticToast(R.string.DialogDiagnosticsFailed);
                }
            } else if (!DialogDiagnostics.isEnabled() || DialogDiagnostics.isFull()) {
                diagnosticToast(DialogDiagnostics.isFull() ? R.string.DialogDiagnosticsFull : R.string.DialogDiagnosticsPausedHint);
            } else if (which == 1) {
                DialogDiagnostics.snapshot(currentAccount, () -> diagnosticToast(R.string.DialogDiagnosticsSnapshotDone));
            } else if (which == 2) {
                diagnosticToast(R.string.DialogDiagnosticsProbeStarted);
                DialogDiagnostics.probe(currentAccount, success -> diagnosticToast(success
                        ? R.string.DialogDiagnosticsProbeDone : R.string.DialogDiagnosticsProbeIncomplete));
            }
        });
        builder.setNegativeButton(getString(R.string.Close), null);
        showDialog(builder.create());
    }

    private void diagnosticToast(int string) {
        Activity activity = getParentActivity();
        if (activity != null) Toast.makeText(activity, getString(string), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != EXPORT_DIALOG_DIAGNOSTICS || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Activity activity = getParentActivity();
        if (activity == null) return;
        Uri destination = data.getData();
        DialogDiagnostics.export(activity.getApplicationContext(), destination, currentAccount,
                success -> diagnosticToast(success ? R.string.DialogDiagnosticsExportDone : R.string.DialogDiagnosticsFailed));
    }

    private void shiftRowsAfterLogsEnabled() {
        donateInfoRow += 2;
        linksHeaderRow += 2;
        forkChannelRow += 2;
        xChannelRow += 2;
        channelTipsRow += 2;
        sourceCodeRow += 2;
        datacenterStatusRow += 2;
        linksShadowRow += 2;
        rowCount += 2;
    }

    private void shiftRowsAfterLogsDisabled() {
        donateInfoRow -= 2;
        linksHeaderRow -= 2;
        forkChannelRow -= 2;
        xChannelRow -= 2;
        channelTipsRow -= 2;
        sourceCodeRow -= 2;
        datacenterStatusRow -= 2;
        linksShadowRow -= 2;
        rowCount -= 2;
    }

    private void showUpdatesDialog() {
        BottomBuilder builder = new BottomBuilder(getParentActivity());
        builder.addTitle(getString(R.string.CheckUpdate));
        builder.addItem(getString(R.string.CheckUpdate), R.drawable.msg_retry, (it) -> {
            Browser.openUrl(getParentActivity(), "tg://update");
            return Unit.INSTANCE;
        });
        builder.addItem(getString(R.string.AutoCheckUpdateSwitch) + " - " + getUpdateChannelDetail(), R.drawable.msg_channel, (it) -> {
            showUpdateChannelDialog();
            return Unit.INSTANCE;
        });
        builder.show();
    }

    private void showUpdateChannelDialog() {
        BottomBuilder switchBuilder = new BottomBuilder(getParentActivity());
        switchBuilder.addTitle(getString(R.string.AutoCheckUpdateSwitch));
        switchBuilder.addRadioItem(getString(R.string.AutoCheckUpdateOFF), NaConfig.INSTANCE.getAutoUpdateChannel().Int() == UpdateHelper.UPDATE_OFF, (radioButtonCell) -> {
            NaConfig.INSTANCE.getAutoUpdateChannel().setConfigInt(UpdateHelper.UPDATE_OFF);
            switchBuilder.doRadioCheck(radioButtonCell);
            AndroidUtilities.runOnUIThread(() -> {
                switchBuilder.dismiss();
                UpdateHelper.cleanAppUpdate();
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(updatesRow);
                }
            }, 500);
            return Unit.INSTANCE;
        });
        switchBuilder.addRadioItem(getString(R.string.AutoCheckUpdateRelease), NaConfig.INSTANCE.getAutoUpdateChannel().Int() == UpdateHelper.UPDATE_CHANNEL_RELEASE, (radioButtonCell) -> {
            NaConfig.INSTANCE.getAutoUpdateChannel().setConfigInt(UpdateHelper.UPDATE_CHANNEL_RELEASE);
            switchBuilder.doRadioCheck(radioButtonCell);
            AndroidUtilities.runOnUIThread(() -> {
                switchBuilder.dismiss();
                Browser.openUrl(getParentActivity(), "tg://update");
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(updatesRow);
                }
            }, 500);
            return Unit.INSTANCE;
        });
        switchBuilder.addRadioItem(getString(R.string.AutoCheckUpdateBeta), NaConfig.INSTANCE.getAutoUpdateChannel().Int() == UpdateHelper.UPDATE_CHANNEL_BETA, (radioButtonCell) -> {
            NaConfig.INSTANCE.getAutoUpdateChannel().setConfigInt(UpdateHelper.UPDATE_CHANNEL_BETA);
            switchBuilder.doRadioCheck(radioButtonCell);
            AndroidUtilities.runOnUIThread(() -> {
                switchBuilder.dismiss();
                Browser.openUrl(getParentActivity(), "tg://update");
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(updatesRow);
                }
            }, 500);
            return Unit.INSTANCE;
        });
        showDialog(switchBuilder.create());
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == infoHeaderRow) {
                        headerCell.setText(getString(R.string.NaxAboutInfo));
                    } else if (position == linksHeaderRow) {
                        headerCell.setText(getString(R.string.NaxLinks));
                    }
                    break;
                case TYPE_DETAIL_SETTINGS:
                    TextDetailSettingsCell detailCell = (TextDetailSettingsCell) holder.itemView;
                    if (position == versionRow) {
                        detailCell.setMultilineDetail(true);
                        detailCell.setTextAndValue(getSimpleVersion(), getString(R.string.NaxAboutDesc), false);
                    }
                    break;
                case TYPE_TEXT:
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position == updatesRow) {
                        textCell.setTextAndValueAndIcon(getString(R.string.CheckUpdate), getUpdateChannelDetail(), R.drawable.msg_retry, true);
                    } else if (position == dialogDiagnosticsRow) {
                        int status = DialogDiagnostics.isFull() ? R.string.DialogDiagnosticsFull
                                : DialogDiagnostics.isEnabled() ? R.string.DialogDiagnosticsRecording : R.string.DialogDiagnosticsPaused;
                        textCell.setTextAndValueAndIcon(getString(R.string.DialogDiagnosticsTitle), getString(status), R.drawable.bug, true);
                    } else if (position == toggleLogsRow) {
                        textCell.setTextAndIcon(BuildVars.LOGS_ENABLED ? getString(R.string.DebugMenuDisableLogs) : getString(R.string.DebugMenuEnableLogs), R.drawable.bug, sendLogsRow != -1);
                    } else if (position == sendLogsRow) {
                        textCell.setTextAndIcon(getString(R.string.DebugSendLogs), R.drawable.ic_upward, true);
                    } else if (position == clearLogsRow) {
                        textCell.setTextAndIcon(getString(R.string.DebugClearLogs), R.drawable.msg_clear, false);
                    } else if (position == forkChannelRow) {
                        textCell.setTextAndValueAndIcon(getString(R.string.NagramXForkChannel), "@NagramXF", R.drawable.msg_channel, true);
                    } else if (position == xChannelRow) {
                        textCell.setTextAndValueAndIcon(getString(R.string.XChannel), "@NagramX", R.drawable.msg_channel, true);
                    } else if (position == channelTipsRow) {
                        textCell.setTextAndValueAndIcon(getString(R.string.OfficialGroupChat), "@NagramXF_Chat", R.drawable.msg_viewchats, true);
                    } else if (position == sourceCodeRow) {
                        textCell.setTextAndValueAndIcon(getString(R.string.SourceCode), "GitHub", R.drawable.github_logo_white, true);
                    } else if (position == datacenterStatusRow) {
                        textCell.setTextAndIcon(getString(R.string.DatacenterStatus), R.drawable.msg_info, false);
                    }
                    break;
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == donateInfoRow) {
                        infoCell.setBackground(new ColorDrawable(0x00000000));
                        infoCell.setText(AndroidUtilities.replaceSingleTag(getString(R.string.DonateInfo), Theme.key_windowBackgroundWhiteLinkText, 0, () -> showDonateDialog(), resourceProvider));
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == infoHeaderRow || position == linksHeaderRow) {
                return TYPE_HEADER;
            } else if (position == versionRow) {
                return TYPE_DETAIL_SETTINGS;
            } else if (position == linksShadowRow) {
                return TYPE_SHADOW;
            } else if (position == donateInfoRow) {
                return TYPE_INFO_PRIVACY;
            } else {
                return TYPE_TEXT;
            }
        }
    }
}
