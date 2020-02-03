package blindpeople.edu.blindpeopleapp.iplib;

import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.features2d.DMatch;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.opencv.features2d.KeyPoint;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class ObjectRecognizer {

    // Parameters for matching
    public static final double RATIO_TEST_RATIO = 0.92;
    public static final int RATIO_TEST_MIN_NUM_MATCHES = 32;
    private FeatureDetector detector;
    private DescriptorExtractor descriptor;
    private DescriptorMatcher matcher;
    private ArrayList<Mat> trainImages;
    private ArrayList<MatOfKeyPoint> trainKeypoints;
    private ArrayList<Mat> trainDescriptors;
    private ArrayList<String> objectNames;
    private MatchingStrategy matchingStrategy = MatchingStrategy.RATIO_TEST;
    private int numMatches;
    private int matchIndex;
    private int[] numMatchesInImage;


    /**
     * TRAIN THE SYSTEM FOR RECOGNITION,
     * IT READS TEH FILE FRIM SPECCIFIED DIRECTORY
     * CALCULATE THE DESCRIPTOR AND KEYPOINTS USING ORB ALGORITHM
     *
     * @param trainDir
     */
    public ObjectRecognizer(File trainDir) {

        ArrayList<File> jpgFiles = Utilities.getJPGFiles(trainDir);
        trainImages = Utilities.getImageMats(jpgFiles);
        objectNames = Utilities.getFileNames(jpgFiles);

        detector = FeatureDetector.create(FeatureDetector.ORB);
        descriptor = DescriptorExtractor.create(DescriptorExtractor.ORB);
        matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE);

        trainKeypoints = new ArrayList<MatOfKeyPoint>();
        trainDescriptors = new ArrayList<Mat>();

        for (int i = 0; i < trainImages.size(); i++) {
            trainKeypoints.add(new MatOfKeyPoint());
            detector.detect(trainImages.get(i), trainKeypoints.get(i));
            trainDescriptors.add(new Mat());
            descriptor.compute(trainImages.get(i), trainKeypoints.get(i),
                    trainDescriptors.get(i));
        }
        matcher.add(trainDescriptors);
        matcher.train();
    }

    /**
     * REMOVE THE INDEXED OBJECT FROM ARRAYLIST
     *
     * @param clickedImgIdx
     */
    public void removeObject(int clickedImgIdx) {
        trainImages.remove(clickedImgIdx);
        objectNames.remove(clickedImgIdx);
        trainKeypoints.remove(clickedImgIdx);
        trainDescriptors.remove(clickedImgIdx);

        matcher.clear();
        matcher.add(trainDescriptors);
        matcher.train();
    }

    /**
     * RECOGNIZE THE OBJECT FROM THE mat OF GRAY IMAGES
     *
     * @param mGray
     * @return
     */
    public String recognize(Mat mGray) {
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        List<MatOfDMatch> matches = new LinkedList<MatOfDMatch>();

        detector.detect(mGray, keypoints);
        descriptor.compute(mGray, keypoints, descriptors);
        String dir = "";
        String object = match(keypoints, descriptors, matches, matchingStrategy);

        try {

            KeyPoint[] refKp = keypoints.toArray();
            Point[] refPts = new Point[refKp.length];

            for (int i = 0; i < refKp.length; i++) {
                refPts[i] = refKp[i].pt;
            }

            MatOfPoint2f refMatPt = new MatOfPoint2f(refPts);
            MatOfPoint2f approxCurve = new MatOfPoint2f();
            //Processing on mMOP2f1 which is in type MatOfPoint2f
            double approxDistance = Imgproc.arcLength(refMatPt, false) * 0.02;
            Imgproc.approxPolyDP(refMatPt, approxCurve, approxDistance, true);
            //Convert back to MatOfPoint
            MatOfPoint points = new MatOfPoint(approxCurve.toArray());
            Rect r = Imgproc.boundingRect(points);

            if (object.equals("-")) {
            } else {
                int X = (int) r.tl().x;
                int screenhalfWidth = mGray.width() / 2;
                if (X <= screenhalfWidth) {
                    dir += "Direction LEFT Object ";
                } else if (X > screenhalfWidth) {
                    dir += "Direction RIGHT Object ";
                }
            }
        } catch (Exception E) {
        }

        return dir + object;
    }

    public String match(MatOfKeyPoint keypoints, Mat descriptors,
                        List<MatOfDMatch> matches, MatchingStrategy matchingStrategy) {
        return match_ratioTest(descriptors, matches, RATIO_TEST_RATIO,
                RATIO_TEST_MIN_NUM_MATCHES);

    }

    private String match_ratioTest(Mat descriptors, List<MatOfDMatch> matches,
                                   double ratio, int minNumMatches) {
        getMatches_ratioTest(descriptors, matches, ratio);
        return getDetectedObjIndex(matches, minNumMatches);
    }


    /**
     * adds to the matches list matches that satisfy the ratio test with ratio
     *
     * @param descriptors
     * @param matches
     * @param ratio
     */
    private void getMatches_ratioTest(Mat descriptors,
                                      List<MatOfDMatch> matches, double ratio) {
        LinkedList<MatOfDMatch> knnMatches = new LinkedList<MatOfDMatch>();
        DMatch bestMatch, secondBestMatch;

        matcher.knnMatch(descriptors, knnMatches, 2);
        for (MatOfDMatch matOfDMatch : knnMatches) {
            bestMatch = matOfDMatch.toArray()[0];
            secondBestMatch = matOfDMatch.toArray()[1];
            if (bestMatch.distance / secondBestMatch.distance <= ratio) {
                MatOfDMatch goodMatch = new MatOfDMatch();
                goodMatch.fromArray(new DMatch[]{bestMatch});
                matches.add(goodMatch);
            }
        }
    }

    /**
     * uses the list of matches to count the number of matches to each database
     * object. The object with the maximum such number nmax is considered to
     * have been recognized if nmax > minNumMatches.
     * if for a query descriptor there exists multiple matches to train
     * descriptors of the same train image, all such matches are counted as only
     * one match.
     * returns the name of the object detected, or "-" if no object is detected.
     *
     * @param matches
     * @param minNumMatches
     * @return
     */
    private String getDetectedObjIndex(List<MatOfDMatch> matches,
                                       int minNumMatches) {
        numMatchesInImage = new int[trainImages.size()];
        matchIndex = -1;
        numMatches = 0;

        for (MatOfDMatch matOfDMatch : matches) {
            DMatch[] dMatch = matOfDMatch.toArray();
            boolean[] imagesMatched = new boolean[trainImages.size()];
            for (int i = 0; i < dMatch.length; i++) {
                if (!imagesMatched[dMatch[i].imgIdx]) {
                    numMatchesInImage[dMatch[i].imgIdx]++;
                    imagesMatched[dMatch[i].imgIdx] = true;
                }
            }
        }
        for (int i = 0; i < numMatchesInImage.length; i++) {
            if (numMatchesInImage[i] > numMatches) {
                matchIndex = i;
                numMatches = numMatchesInImage[i];
            }
        }
        if (numMatches < minNumMatches) {
            return "-";
        } else {
            return objectNames.get(matchIndex);
        }
    }
}
