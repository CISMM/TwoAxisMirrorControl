package cismm;


import ij.IJ;
import ij.plugin.frame.RoiManager;
import java.awt.Checkbox;
import java.awt.Panel;
import java.awt.event.ItemEvent;
import mmcorej.CMMCore;
import org.micromanager.api.ScriptInterface;
import org.micromanager.utils.GUIUtils;
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author phsiao
 */
public class DualAxisMirrorPlugin implements org.micromanager.api.MMPlugin {

    private ScriptInterface app_;
    private CMMCore core_;
   
    //private MainFrame frame_= null;
    private MirrorControlForm frame_= null;
   
    // Show the ImageJ Roi Manager and return a reference to it.   
   public static RoiManager showRoiManager() {
      IJ.run("ROI Manager...");
      final RoiManager roiManager = RoiManager.getInstance();
      GUIUtils.recallPosition(roiManager);
      // "Get the "Show All" checkbox and make sure it is checked.
      Checkbox checkbox = (Checkbox) ((Panel) roiManager.getComponent(1)).getComponent(9);
      checkbox.setState(true);
      // Simulated click of the "Show All" checkbox to force ImageJ
      // to show all of the ROIs.
      roiManager.itemStateChanged(new ItemEvent(checkbox, 0, null, ItemEvent.SELECTED));
      return roiManager;
   }
   
    /*
    public DualAxisMirrorPlugin() {
        create_daq_bins();
    }
    */
    @Override
    public void dispose() {
        if (frame_ != null) {
            frame_.cleanup();
            frame_.setVisible(false);
            frame_.dispose();
            frame_ = null;
        }
        
        MirrorControlForm.del_daq_bins();
    }

    @Override
    public void setApp(ScriptInterface si) {
        app_ = si;
        core_ = app_.getMMCore();
    }

    @Override
    public void show() {
        if (frame_ == null) {
            frame_ = new MirrorControlForm(core_, app_); 
        }
        else {
         frame_.toFront();
        }
        frame_.setVisible(true);
    }

    @Override
    public String getDescription() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String getInfo() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String getVersion() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String getCopyright() {
        return null;
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
}
