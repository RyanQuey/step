package com.tyndalehouse.step.core.service.impl;

import com.tyndalehouse.step.core.exceptions.StepInternalException;
import com.tyndalehouse.step.core.service.AugDStrongService;
import com.tyndalehouse.step.core.service.jsword.JSwordPassageService;
import com.tyndalehouse.step.core.service.jsword.JSwordVersificationService;

import com.tyndalehouse.step.core.data.create.ModuleLoader;

import com.tyndalehouse.step.core.service.jsword.impl.JSwordPassageServiceImpl;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.crosswire.jsword.passage.*;
import org.crosswire.jsword.versification.Versification;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.tyndalehouse.step.core.utils.IOUtils.closeQuietly;
import static java.lang.Integer.parseInt;
import org.slf4j.Logger;

@Singleton
public class AugDStrongServiceImpl implements AugDStrongService {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(AugDStrongServiceImpl.class);

    private static int numOfStrongGrk;
	private static int numOfAugStrongOT;
    private static int numOfAugStrongNT;
    private static short[] strongs;
    private static short[] strong2AugStrongIndex;
    private static byte[] strong2AugStrongCount;
    private static int[] augStrong2RefIdxOT;
    private static int[] augStrong2RefIdxNT;
    public static short[] refOfAugStrongOTOHB;
    public static short[] refOfAugStrongOTRSV;
    public static short[] refOfAugStrongNT;
    private final JSwordVersificationService versificationService;

@Inject
    public AugDStrongServiceImpl(JSwordVersificationService versificationService) {
        this.versificationService = versificationService;
    }

    public short convertOSIS2Ordinal(final String OSIS, final Versification curVersification) {
        try {
            Verse key = VerseFactory.fromString(curVersification, OSIS);
            int ordinal = key.getOrdinal();
            if ((ordinal > 0) && (ordinal <= 32767)) return (short) ordinal;
        } catch (NoSuchVerseException e) {
            throw new StepInternalException("\"Unable to look up strongs for \" + OSIS ", e);
        }
        return -1;
    }

    private int addToAugStrong2Ref(int refIndex, String augStrong, int lenOfRef) {
        int result = augStrong.charAt(augStrong.length() - 1);
        if ((result < 65) || (result > 122)) { // 65 is A 121 is z
            System.out.println("suffix of augmented Strong " + augStrong + " is outside of range of expected characters: " + augStrong.charAt(augStrong.length() - 1) + " " + result);
            System.exit(4);
        }
        result = result << 24; // the top byte of the integer is the suffix of the augmented strong.  The other 3 byte of the integer is the index to the reference array
        if (lenOfRef == 0) return result; // if there are no reference, then there is no index to the reference array
        return result | refIndex;
    }

    private int addToRefArray(int refIndex, final boolean hebrew, final String refs, final Versification versificationForOT, final Versification versificationForESV) {
        if (refs.equals("")) return refIndex;
        String[] arrOfRef = refs.split(" ");
        int startIndex = refIndex;
        for (String s : arrOfRef) {
            short refOrdinal = (hebrew) ? convertOSIS2Ordinal(s, versificationForOT) : convertOSIS2Ordinal(s, versificationForESV);
            if (refOrdinal > -1) {
                if (hebrew) {
                    refOfAugStrongOTOHB[refIndex] = refOrdinal;
                    refOrdinal = (short) this.versificationService.convertReferenceGetOrdinal(s, versificationForOT, versificationForESV);
                    if (refOrdinal > -1)
                        refOfAugStrongOTRSV[refIndex] = refOrdinal;
                } else
                    refOfAugStrongNT[refIndex] = refOrdinal;
                refIndex++;
            }
        }
        if (hebrew) {
            Arrays.sort(refOfAugStrongOTOHB, startIndex, refIndex);
            Arrays.sort(refOfAugStrongOTRSV, startIndex, refIndex);
        } else Arrays.sort(refOfAugStrongNT, startIndex, refIndex);
        return refIndex;
    }

    private int binarySearchOfStrong(final String augStrong) {
        int first = 0;
        int last = strongs.length - 1;
        if (augStrong.charAt(0) == 'G') {
            last = numOfStrongGrk - 1;
        }
        else {
            first = numOfStrongGrk;
        }
        int key = cnvrtStrong2Short(augStrong);
        int mid = (first + last) / 2;
        while( first <= last ) {
            if ( strongs[mid] < key ) first = mid + 1;
            else if ( strongs[mid] == key ) return mid;
            else last = mid - 1;
            mid = (first + last) / 2;
        }
        return -1;
    }

    private void verifyAugStrongPattern(final String augStrong) {
        char prefix = augStrong.charAt(0);
        if ( (prefix != 'H') && (prefix != 'G') && (prefix != 'h') && (prefix != 'g') ) {
            System.out.println("augmented strong does not start with H or G: " + augStrong);
            System.exit(404);
        }
        char suffix = augStrong.charAt(augStrong.length() - 1);
        if (Character.isDigit(suffix)) {
            System.out.println("Last character of this strong is numeric: " + augStrong);
            System.exit(404);
        }
    }

    private int cnvrtStrong2Short(final String strong) {
        int startPos = 1;
        int endPos = strong.length() - 1;
        char suffix = strong.charAt(endPos);
        if (Character.isDigit(suffix)) endPos++;
        int num = -1;
        try {
            num = parseInt(strong.substring(startPos, endPos)); // If the augmented Strong file has issue, it will run into an exception.
        } catch (NumberFormatException e) {
            LOGGER.error("Strong number is not numeric at the expected positions: " + strong + " Something wrong with the augmented Strong file.");
            System.exit(4);
        }

        if (num > 32767) {
            LOGGER.error("Strong number has too many digits: " + strong + " Something wrong with the augmented Strong file.");
            System.exit(4);
        }
        return num;
    }

    private void addStrongToHashMap(HashMap<Integer, Integer> strong2AugCount, final int num) {
        if (strong2AugCount.containsKey(num)) {
            strong2AugCount.put(num, strong2AugCount.get(num) + 1);
        } else {
            strong2AugCount.put(num, 1);
        }
    }

    private ImmutablePair<Character, Integer> getSuffixAndIdx(int num) {
        Character suffix = (char) (num >> 24);
        int index = num & 0x00FFFFFF;
        return new ImmutablePair<>(suffix, index);
    }

    public void updatePassageKeyWithAugStrong(String strong, Key key) {
        String trimmedStrong = strong.trim();
        boolean isAugStrong = !Character.isDigit(trimmedStrong.charAt(trimmedStrong.length() - 1)); // Last character of augmented strong should not be digit
        if (!isAugStrong) return; // No need to update the key
        final Versification sourceVersification = ((RocketPassage) key).getVersification();
        String versificationName = sourceVersification.getName();
        char prefix = trimmedStrong.charAt(0);
        boolean hebrew = (prefix == 'H') || (prefix == 'h');
        short[] ref = (hebrew) ? refOfAugStrongOTRSV : refOfAugStrongNT;
        Versification versificationForConversion = null;
        if (versificationName.equals(JSwordPassageService.OT_BOOK)) ref = refOfAugStrongOTOHB;
        else if ((!versificationName.equals("NRSV")) && (!versificationName.equals("KJV")))
            versificationForConversion = this.versificationService.getVersificationForVersion("NRSV");
        int[] index = getIndexes2OrdinalOfAugStrong(trimmedStrong);
        if (index == null) return;
        final int index2Ref = index[0];
        final int numOfRef = index[1];
        final boolean emptyRef = index[2] == 0;
        BitSet store = ((RocketPassage) key).store;
        BitSet tmpStore = null;
        if (!emptyRef) tmpStore = new BitSet(store.size());
        for (int i = 0; i < numOfRef; i ++) {
            int ordinal = ref[index2Ref + i];
            if (versificationForConversion != null) {
                String reference = versificationForConversion.decodeOrdinal(ordinal).getOsisRef();
                ordinal = this.versificationService.convertReferenceGetOrdinal(reference, versificationForConversion, sourceVersification);
                if (ordinal < 0) continue;
            }
            if (!emptyRef) {
                if (store.get(ordinal)) tmpStore.set(ordinal);
            }
            else store.clear(ordinal);
        }
        if (!emptyRef) ((RocketPassage) key).store = tmpStore;
    }

    private int[] getIndexes2OrdinalOfAugStrong(String strong) {
        int index1 = binarySearchOfStrong(strong);
        if (index1 < 0) return null;
        short index2 = strong2AugStrongIndex[index1];
        if (index2 < 0) return null;
        int[] augStrong2RefIdx;
        char prefix = strong.charAt(0);
        if ((prefix == 'H') || (prefix == 'h')) {
            if (index2 > numOfAugStrongOT) return null;
            augStrong2RefIdx = augStrong2RefIdxOT;
        }
        else {
            if (index2 > numOfAugStrongNT) return null;
            augStrong2RefIdx = augStrong2RefIdxNT;
        }
        final int numOfAugStrongWithSameStrong = strong2AugStrongCount[index1];
        char lastCharOfStrong = strong.charAt(strong.length() - 1);
        int suffixInt = (lastCharOfStrong & 0x000000ff) << 24;
        int[] result = new int[3];
        for (int i = index2 + numOfAugStrongWithSameStrong - 1; i >= index2; i--) {
            int curPtr = augStrong2RefIdx[i];
            int checkSuffix = curPtr & 0x7f000000;  // Don't copy over the sign bit
            int check = checkSuffix >> 24;
            System.out.println("getIndex2Ord " + lastCharOfStrong + " " + check);
            if (checkSuffix == suffixInt) {
                result[0] = curPtr & 0x00ffffff; // index to list of ordinal (verse) for aug strong
                if (result[0] == 0) {
                    result[0] = augStrong2RefIdx[index2] & 0x00ffffff; // index to list of ordinal (verse) for aug strong
                    result[1] = (augStrong2RefIdx[index2 + numOfAugStrongWithSameStrong] & 0x00ffffff) - result[0]; // length
                    return result;
                }
                curPtr = augStrong2RefIdx[i + 1];
                result[1] = (curPtr & 0x00ffffff) - result[0]; // length
                result[2] = 1;
                return result;
            }
        }
        return null;
    }

    public AugmentedStrongsForSearchCount getRefIndexWithStrongAndVersification(final String strong, final Versification sourceVersification) {
        char prefix = strong.charAt(0);
        boolean hebrew = ((prefix == 'H') || (prefix == 'h'));
        String versificationName = sourceVersification.getName();
        boolean useNRSVVersification = false;
        boolean convertVersification = false;
        if ((versificationName.equals("NRSV")) || (versificationName.equals("KJV"))) {
            useNRSVVersification = true;
        }
        else if (!versificationName.equals(JSwordPassageService.OT_BOOK)) convertVersification = true;
        int index1 = binarySearchOfStrong(strong);
        if (index1 < 0) return null;
        short index2 = strong2AugStrongIndex[index1];
        int[] augStrong2RefIdx;
        short[] refArray;
        if (hebrew) {
            if ((index2 < 0) || (index2 > numOfAugStrongOT)) return null;
            augStrong2RefIdx = augStrong2RefIdxOT;
            refArray = (useNRSVVersification) ? refOfAugStrongOTRSV : refOfAugStrongOTOHB;
        } else if ((prefix == 'G') || (prefix == 'g')) {
            if ((index2 < 0) || (index2 > numOfAugStrongNT)) return null;
            augStrong2RefIdx = augStrong2RefIdxNT;
            refArray = refOfAugStrongNT;
        } else return null;
        int numOfAugStrongWithSameStrong = strong2AugStrongCount[index1];
        char suffix = strong.charAt(strong.length()-1);
        for (int i = 0; i < numOfAugStrongWithSameStrong; i++) {
            ImmutablePair<Character, Integer> r = getSuffixAndIdx(augStrong2RefIdx[index2 + i]);
            char curSuffix = r.getLeft();
            if (curSuffix == suffix) {
                int curIndex = r.getRight();
                boolean defaultAugStrong = false;
                int augStrong2RefIdxNextIdx ;
                int start;
                if (curIndex == 0) {
                    defaultAugStrong = true;
                    start = getSuffixAndIdx(augStrong2RefIdx[index2]).getRight();
                    augStrong2RefIdxNextIdx = index2 + numOfAugStrongWithSameStrong;
                }
                else {
                    start = r.getRight();
                    augStrong2RefIdxNextIdx = index2 + i + 1;
                }
                int endIndexOfCurrentAugStrongRef = 0;
                while (endIndexOfCurrentAugStrongRef == 0) {
                    endIndexOfCurrentAugStrongRef = getSuffixAndIdx(augStrong2RefIdx[augStrong2RefIdxNextIdx]).getRight(); // Next entry in augStrong2RefPtr
                    augStrong2RefIdxNextIdx ++;
                }
                endIndexOfCurrentAugStrongRef --;
                return new AugmentedStrongsForSearchCount(start, endIndexOfCurrentAugStrongRef, defaultAugStrong, convertVersification, refArray);
            }
        }
        return null;
    }

    public boolean isVerseInAugStrong(String reference, AugmentedStrongsForSearchCount arg, final Versification sourceVersification) {
        int ordinal;
        if (arg.convertVersification)
            ordinal = this.versificationService.convertReferenceGetOrdinal(reference, sourceVersification, this.versificationService.getVersificationForVersion(JSwordPassageService.OT_BOOK));
        else
            ordinal = convertOSIS2Ordinal(reference, sourceVersification);
        for (int i = arg.startIndex; i <= arg.endIndex; i ++) {
            if (arg.refArray[i] == ordinal)
                return !arg.defaultAugStrong;
        }
        return arg.defaultAugStrong;
    }

    public String getAugStrongWithStrongAndOrdinal(final String strong, final int ordinal, final boolean useNRSVVersification) {
        if ((ordinal < 0) || (ordinal > 32767)) return "";
        int index1 = binarySearchOfStrong(strong);
        if (index1 < 0) return strong;
        short index2 = strong2AugStrongIndex[index1];
        int[] augStrong2RefIdx;
        char prefix = strong.charAt(0);
        short[] refArray;
        if ((prefix == 'H') || (prefix == 'h')) {
            if ((index2 < 0) || (index2 > numOfAugStrongOT)) return "";
            augStrong2RefIdx = augStrong2RefIdxOT;
            refArray = (useNRSVVersification) ? refOfAugStrongOTRSV : refOfAugStrongOTOHB;
        } else if ((prefix == 'G') || (prefix == 'g')) {
            if ((index2 < 0) || (index2 > numOfAugStrongNT)) return "";
            augStrong2RefIdx = augStrong2RefIdxNT;
            refArray = refOfAugStrongNT;
        } else return "";
        int numOfAugStrongWithSameStrong = strong2AugStrongCount[index1];
        int index2LastAugStrongWithSameStrong = index2 + numOfAugStrongWithSameStrong - 1;
        int augStrong2RefIdxNextIdx = index2LastAugStrongWithSameStrong;
        int endIndexOfCurrentStrongRef = 0;
        while (endIndexOfCurrentStrongRef == 0) {
            augStrong2RefIdxNextIdx ++;
            int index2FirstAugStrongWithNextStrong = augStrong2RefIdx[augStrong2RefIdxNextIdx];
            endIndexOfCurrentStrongRef = getSuffixAndIdx(index2FirstAugStrongWithNextStrong).getRight(); // Next entry in augStrong2RefPtr
        }
        int endIndexOfCurrentAugStrongRef = endIndexOfCurrentStrongRef - 1;
        char suffixWithNoRefs = ' ';
        for (int i = index2LastAugStrongWithSameStrong; i >= index2; i--) {
            ImmutablePair<Character, Integer> r = getSuffixAndIdx(augStrong2RefIdx[i]);
            char curSuffix = r.getLeft();
            int curIndex = r.getRight();
            if (curIndex == 0)
                suffixWithNoRefs = curSuffix;
            else {
//            System.out.println("getAugStrongWithStrongAndOrdinal " + strong + " " + curSuffix);
                if ((endIndexOfCurrentAugStrongRef - curIndex) > 50) { // If the array of reference (in ordinal) is large, the binary search is faster.
                    // if the binary search has any issue, remove the binary search because the performance improvement is not that big.
                    int bSearchResult = Arrays.binarySearch(refArray, curIndex, endIndexOfCurrentAugStrongRef+1, (short) ordinal);
                    if (bSearchResult > -1) return strong + curSuffix;
                }
                else { // If the array of reference (in ordinal) is small, a sequential search is faster.
                    for (int x = curIndex; x <= endIndexOfCurrentAugStrongRef; x++) {
                        // the array of reference (in ordinal) are sorted.  When it reaches an ordinal in the reference array which is larger, that ordinal does not exist in the reference array.
                        // breaking out of the for loop will reduce unnecessary processing
                        if (refArray[x] > ordinal) break;
                        if (refArray[x] == ordinal) return strong + curSuffix;
                    }
                }
            }
            endIndexOfCurrentAugStrongRef = curIndex - 1;
        }
        if (suffixWithNoRefs != ' ') return strong + suffixWithNoRefs;
        return strong;
    }

	public void readAndLoad(final String augStrongFile) {
        Reader fileReader = null;
        InputStream stream = null;
        BufferedInputStream bufferedStream;
        String curAugStrong = "";
        String curReferences = "";
        int numOfOTReferences = 0;
        int numOfNTReferences = 0;
        HashMap<Integer, Integer> strong2AugCountGrk = new HashMap<>();
        HashMap<Integer, Integer> strong2AugCountHbr = new HashMap<>();
        HashMap<String, String> augStrongRefOT = new HashMap<>();
        HashMap<String, String> augStrongRefNT = new HashMap<>();
        try {
            stream = ModuleLoader.class.getResourceAsStream(augStrongFile);
            if (stream == null) {
                throw new StepInternalException("Unable to read resource: " + augStrongFile);
            }
            bufferedStream = new BufferedInputStream(stream);
            fileReader = new InputStreamReader(bufferedStream, StandardCharsets.UTF_8);
            final BufferedReader bufferedReader = new BufferedReader(fileReader);
            String data;
            try {
                boolean hebrew = false;
                while ((data = bufferedReader.readLine()) != null) {
                    if (data.endsWith("=======================")) {
                        if (!curAugStrong.equals("")) {
                            System.out.println("unexpected order at around " + curAugStrong);
                            System.exit(401);
                        }
                        else if (!curReferences.equals("")) {
                            System.out.println("unexpected order at around " + curReferences);
                            System.exit(401);
                        }
                    }
                    else if (data.startsWith("@AugmentedStrong=\t")) {
                        if (!curAugStrong.equals("")) {
                            System.out.println("unexpected order at around " + curAugStrong);
                            System.exit(403);
                        }
                        curAugStrong = data.substring(18);
                        verifyAugStrongPattern(curAugStrong);
                        int num = cnvrtStrong2Short(curAugStrong);
                        if (curAugStrong.charAt(0) == 'H') {
                            addStrongToHashMap(strong2AugCountHbr, num);
                            hebrew = true;
                        }
                        else {
                            addStrongToHashMap(strong2AugCountGrk, num);
                            hebrew = false;
                        }
                    }
                    else if (data.startsWith("@References=\t")) {
                        if (!curReferences.equals("")) {
                            System.out.println("unexpected order at around " + curReferences);
                            System.exit(401);
                        }
                        curReferences = data.substring(13);
                        if (hebrew) {
                            if (augStrongRefOT.containsKey(curAugStrong)) {
                                System.out.println("duplicate augmented strong " + curAugStrong);
                                continue;
                            }
                            augStrongRefOT.put(curAugStrong, curReferences);
                        }
                        else {
                            if (augStrongRefNT.containsKey(curAugStrong)) {
                                System.out.println("duplicate augmented strong " + curAugStrong);
                                continue;
                            }
                            augStrongRefNT.put(curAugStrong, curReferences);
                        }
                        if (!curReferences.equals("")) {
                            String[] arrOfRef = curReferences.split(" ");
                            if (hebrew) numOfOTReferences += arrOfRef.length;
                            else numOfNTReferences += arrOfRef.length;
                        }
                        curAugStrong = ""; curReferences = ""; hebrew = false;
                    }
                }
            } catch (final IOException e) {
                throw new StepInternalException("Unable to read a line from the source file ", e);
            }
            numOfStrongGrk = strong2AugCountGrk.size();
            int numOfStrong = numOfStrongGrk + strong2AugCountHbr.size();
            numOfAugStrongOT = augStrongRefOT.size();
            numOfAugStrongNT = augStrongRefNT.size();
            strongs=new short[numOfStrong];
            strong2AugStrongIndex =new short[numOfStrong];
            strong2AugStrongCount=new byte[numOfStrong];
            augStrong2RefIdxOT =new int[numOfAugStrongOT+1];
            augStrong2RefIdxNT =new int[numOfAugStrongNT+1];
            TreeMap<Integer, Integer> sortedStrongGrk = new TreeMap<>(strong2AugCountGrk);
            int counter = 0;
            for (Map.Entry<Integer, Integer> entry : sortedStrongGrk.entrySet()) {
                strongs[counter] = entry.getKey().shortValue();
                strong2AugStrongCount[counter] = entry.getValue().byteValue();
                counter ++;
            }
            TreeMap<Integer, Integer> sortedStrongHbr = new TreeMap<>(strong2AugCountHbr);
            for (Map.Entry<Integer, Integer> entry : sortedStrongHbr.entrySet()) {
                strongs[counter] = entry.getKey().shortValue();
                strong2AugStrongCount[counter] = entry.getValue().byteValue();
                counter ++;
            }
            TreeMap<String, String> sortedAugStrong = new TreeMap<>();
            sortedAugStrong.putAll(augStrongRefNT);
            sortedAugStrong.putAll(augStrongRefOT);
            int strong2AugStrongIndexOT = 0;
            int strong2AugStrongIndexNT = 0;
            int refIndexOT = 0;
            int refIndexNT = 0;
            int lastStrong = 32767;
            final Versification versificationForOT = this.versificationService.getVersificationForVersion(JSwordPassageServiceImpl.OT_BOOK);
            final Versification versificationForESV = this.versificationService.getVersificationForVersion("ESV");
            int strongNumWithMostReferences = -1;
            String augStrongWithMostReferences = "";
            int mostReferencesWithinAugStrongs = 0;
            for (Map.Entry<String, String> entry : sortedAugStrong.entrySet()) {
                String augStrong = entry.getKey();
                int curStrongNum = cnvrtStrong2Short(augStrong);
                if (strongNumWithMostReferences != curStrongNum) {
                    if (strongNumWithMostReferences > -1) {
                        String[] arrOfRef = sortedAugStrong.get(augStrongWithMostReferences).split(" ");
                        sortedAugStrong.put(augStrongWithMostReferences, "");
                        char prefix = augStrongWithMostReferences.charAt(0);
                        if ((prefix == 'H') || (prefix == 'h'))
                            numOfOTReferences -= arrOfRef.length;
                        else numOfNTReferences -=  arrOfRef.length;
                    }
                    strongNumWithMostReferences = curStrongNum;
                    augStrongWithMostReferences = "";
                    mostReferencesWithinAugStrongs = 0;
                }
                String references = entry.getValue();
                String[] arrOfRef = references.split(" ");
                if (mostReferencesWithinAugStrongs < arrOfRef.length) {
                    mostReferencesWithinAugStrongs = arrOfRef.length;
                    augStrongWithMostReferences = augStrong;
                }
            }
            if (strongNumWithMostReferences > -1) {
                String[] arrOfRef = sortedAugStrong.get(augStrongWithMostReferences).split(" ");
                sortedAugStrong.put(augStrongWithMostReferences, "");
                char prefix = augStrongWithMostReferences.charAt(0);
                if ((prefix == 'H') || (prefix == 'h')) {
                    numOfOTReferences -= arrOfRef.length;
                } else numOfNTReferences -=  arrOfRef.length;
            }
            refOfAugStrongOTOHB = new short[numOfOTReferences];
            refOfAugStrongOTRSV = new short[numOfOTReferences];
            refOfAugStrongNT = new short[numOfNTReferences];
            for (Map.Entry<String, String> entry : sortedAugStrong.entrySet()) {
                String augStrong = entry.getKey();
                String references = entry.getValue();
                int curStrongNum = cnvrtStrong2Short(augStrong);
                boolean hebrew = false;
                char prefix = augStrong.charAt(0);
                if ((prefix == 'H') || (prefix == 'h')) {
                    augStrong2RefIdxOT[strong2AugStrongIndexOT] = addToAugStrong2Ref(refIndexOT, augStrong, references.length());
                    hebrew = true;
                }
                else {
                    augStrong2RefIdxNT[strong2AugStrongIndexNT] = addToAugStrong2Ref(refIndexNT, augStrong, references.length());
                }
                if (lastStrong != curStrongNum) {
                    int index = binarySearchOfStrong(augStrong);
                    if (index < 0) {
                        LOGGER.error("Error in AugStrongServiceImpl, cannot find augstrong of " + augStrong);
                        System.exit(405);
                    }
                    strong2AugStrongIndex[index] = (hebrew) ? (short) strong2AugStrongIndexOT : (short) strong2AugStrongIndexNT;
                    lastStrong = curStrongNum;
                }
                if (hebrew) {
                    strong2AugStrongIndexOT ++;
                    refIndexOT = addToRefArray(refIndexOT, true, references, versificationForOT, versificationForESV);
                }
                else {
                    strong2AugStrongIndexNT ++;
                    refIndexNT = addToRefArray(refIndexNT, false, references, versificationForOT, versificationForESV);
                }
            }
            augStrong2RefIdxOT[strong2AugStrongIndexOT] = refIndexOT;
            augStrong2RefIdxNT[strong2AugStrongIndexNT] = refIndexNT;
            strong2AugCountGrk = null;
            strong2AugCountHbr = null;
            augStrongRefOT = null;
            augStrongRefNT = null;
            sortedStrongGrk = null;
            sortedStrongHbr = null;
            sortedAugStrong = null;
            System.gc(); // Free memory that will never be used after the initial load.  This like is probably unnecessary but just in case.
        } finally {
            closeQuietly(fileReader);
            // closeQuietly(bufferedStream);
            closeQuietly(stream);
        }
	}

}
