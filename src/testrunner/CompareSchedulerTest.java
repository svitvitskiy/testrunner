package testrunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;

import testrunner.CompareScheduler.Descriptor;
import testrunner.CompareScheduler.JobRequest;
import testrunner.CompareScheduler.JobResult;

public class CompareSchedulerTest {
    
    public static JobResult helper(String filename, int ptIdx, double dist[], int rate, int enIdx, boolean valid) {
        return new JobResult(new JobRequest(null, null, -1, null, null, filename, enIdx, ptIdx),
                valid, null, rate, dist);
    }
    
    @Test
    public void testGenerateReport() throws IOException {
        List<TestScheduler.JobResult> results = new ArrayList<TestScheduler.JobResult>();

        for (int enIdx = 0; enIdx < 2; enIdx++) {
            results.add(helper("akiyo_cif",      0,   new double[] {32.142722-enIdx*0.01,60.080329-enIdx*0.01,   0.893-enIdx*0.01},9984  , enIdx, true));
            results.add(helper("akiyo_cif",      7,   new double[] {44.412459-enIdx*0.01,95.594065-enIdx*0.01,0.981942-enIdx*0.01},113164, enIdx, true));
            results.add(helper("akiyo_cif",      12,  new double[] {44.977018-enIdx*0.01,96.139412-enIdx*0.01,0.983966-enIdx*0.01},113332, enIdx, true));
            results.add(helper("coastguard_cif", 0,   new double[] {24.426146-enIdx*0.01,40.455356-enIdx*0.01, 0.54985-enIdx*0.01},13414 , enIdx, true));
            results.add(helper("coastguard_cif", 6,   new double[] {30.257619-enIdx*0.01,82.029645-enIdx*0.01,0.839506-enIdx*0.01},134294, enIdx, true));
            results.add(helper("coastguard_cif", 12,  new double[] {36.102752-enIdx*0.01,97.050597-enIdx*0.01,0.949877-enIdx*0.01},533332, enIdx, true));
            results.add(helper("foreman_cif",    0,   new double[] {26.463655-enIdx*0.01, 37.94201-enIdx*0.01, 0.74453-enIdx*0.01},12672 , enIdx, true));
            results.add(helper("foreman_cif",    6,   new double[] {37.276975-enIdx*0.01,94.700328-enIdx*0.01,0.938189-enIdx*0.01},136827, enIdx, true));
            results.add(helper("foreman_cif",    12,  new double[] { 42.12298-enIdx*0.01,99.493608-enIdx*0.01,0.974082-enIdx*0.01},466347, enIdx, true));
            results.add(helper("ice_cif",        0,   new double[] {27.942097-enIdx*0.01,42.291277-enIdx*0.01,0.866808-enIdx*0.01},17006 , enIdx, true));
            results.add(helper("ice_cif",        6,   new double[] { 40.24278-enIdx*0.01,96.841157-enIdx*0.01,  0.9743-enIdx*0.01},131785, enIdx, true));
            results.add(helper("ice_cif",        12,  new double[] {45.246704-enIdx*0.01,99.809873-enIdx*0.01,0.986364-enIdx*0.01},308008, enIdx, true));
            results.add(helper("paris_cif",      0,   new double[] {23.142485-enIdx*0.01,47.309673-enIdx*0.01,0.713924-enIdx*0.01},16492 , enIdx, false));
            results.add(helper("paris_cif",      5,   new double[] {31.058826-enIdx*0.01,85.514402-enIdx*0.01,0.914803-enIdx*0.01},89878 , enIdx, true));
            results.add(helper("paris_cif",      12,  new double[] {41.750851-enIdx*0.01,98.390682-enIdx*0.01,0.985585-enIdx*0.01},522875, enIdx, true));
            results.add(helper("tempete_cif",    0,   new double[] {23.220217-enIdx*0.01,38.955947-enIdx*0.01,0.656927-enIdx*0.01},14166 , enIdx, true));
            results.add(helper("tempete_cif",    6,   new double[] {31.011667-enIdx*0.01,85.543353-enIdx*0.01,0.925756-enIdx*0.01},133614, enIdx, true));
            results.add(helper("tempete_cif",    12,  new double[] {36.960156-enIdx*0.01, 96.71093-enIdx*0.01,0.975625-enIdx*0.01},532522, enIdx, true));
            results.add(helper("bowing_cif",     0,   new double[] {32.139012-enIdx*0.01,64.770373-enIdx*0.01,0.890557-enIdx*0.01},10495 , enIdx, true));
            results.add(helper("bowing_cif",     6,   new double[] {44.572001-enIdx*0.01,96.757242-enIdx*0.01,0.985567-enIdx*0.01},99370 , enIdx, true));
            results.add(helper("bowing_cif",     12,  new double[] {45.417616-enIdx*0.01,97.654752-enIdx*0.01,0.987156-enIdx*0.01},98088 , enIdx, true));
            results.add(helper("container_cif",  0,   new double[] {27.958382-enIdx*0.01,64.125325-enIdx*0.01,0.790713-enIdx*0.01},10141 , enIdx, true));
            results.add(helper("container_cif",  6,   new double[] {38.760628-enIdx*0.01,94.815499-enIdx*0.01,0.948438-enIdx*0.01},134589, enIdx, true));
            results.add(helper("container_cif",  12,  new double[] {42.026635-enIdx*0.01,96.740439-enIdx*0.01,0.971398-enIdx*0.01},321903, enIdx, true));
            results.add(helper("garden_sif",     0,   new double[] { 19.61018-enIdx*0.01,45.569822-enIdx*0.01,0.612233-enIdx*0.01},29801 , enIdx, true));
            results.add(helper("garden_sif",     5,   new double[] {22.404837-enIdx*0.01,68.286481-enIdx*0.01,0.765414-enIdx*0.01},76630 , enIdx, true));
            results.add(helper("garden_sif",     12,  new double[] {30.495074-enIdx*0.01,98.444086-enIdx*0.01,0.945593-enIdx*0.01},444510, enIdx, true));
        }
        
        List<String> dataset = Arrays.asList(new String[] {});
        String[] encBin = new String[] {"stan", "cool"};
        String[] encName = new String[] {"Stan", "Cool"};
        
        Descriptor descriptor = new Descriptor(dataset, encBin, encName, 0, "profile name", "codec", "mode", 99, "common args", new String[] {"diff args", "diff args"}, new HashMap<String, String>());
        CompareScheduler.generateReport(results, descriptor, new File(System.getProperty("user.home") + "/Desktop/report.html"));
    }
}
