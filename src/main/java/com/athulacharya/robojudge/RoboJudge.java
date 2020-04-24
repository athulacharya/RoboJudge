/*
 * Copyright 2020 Athul K. Acharya
 * Derived from Google's InfiniteStreamRecognize.java at [1]; relevant portions (c) 2018 Google LLC.
 *
 * [1] https://github.com/GoogleCloudPlatform/java-docs-samples/tree/master/speech/cloud-client/src/main/java/com/example/speech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.athulacharya.robojudge;

// [START speech_transcribe_infinite_streaming]

import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v1p1beta1.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;

import javax.sound.sampled.*;
import javax.sound.sampled.DataLine.Info;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RoboJudge {

    private static final int STREAMING_LIMIT = 290000; // ~5 minutes

    private static String[] keywords = {
            "Benitez",
            "fourth year",
            "fifth year",
            "notorious",
            "letterhead"
    };

    private static final String TRANSCRIPT_MAP_FILE = ".transcript_map";
    private static final String KEYWORD_MAP_FILE = ".keywords_map";

    private static List<String> hints = null;
    private static List<ByteString> questions = null;
    private static HashMap<Integer, String> transcriptMap = new HashMap<>();
    private static HashMap<String, ArrayList<Integer>> keywordMap = new HashMap<>();

    public static final String RED = "\033[0;31m";
    public static final String GREEN = "\033[0;32m";
    public static final String YELLOW = "\033[0;33m";
    public static final String BLUE = "\033[0;34m";

    // Creating shared object
    private static volatile BlockingQueue<byte[]> sharedQueue = new LinkedBlockingQueue<>();
    private static TargetDataLine targetDataLine;
    private static int BYTES_PER_BUFFER = 6400; // buffer size in bytes

    private static int restartCounter = 0;
    private static ArrayList<ByteString> audioInput = new ArrayList<>();
    private static ArrayList<ByteString> lastAudioInput = new ArrayList<>();
    private static int resultEndTimeInMS = 0;
    private static int isFinalEndTime = 0;
    private static int finalRequestEndTime = 0;
    private static boolean newStream = true;
    private static double bridgingOffset = 0;
    private static boolean lastTranscriptWasFinal = false;
    private static StreamController referenceToStreamController;
    private static ByteString tempByteString;

    // helper method to read file bytes inside Stream.map
    private static byte[] readFileBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ByteString getQuestion(int hashCode) {
        for (ByteString q : questions) {
            if (q.hashCode() == hashCode) return q;
        }

        return null;
    }

    // load hints from file if we have them
    private static void parseHints(RoboJudgeCLIOptions options) {
        if (options.hintsFile != null) {
            try {
                Path path = Paths.get(options.hintsFile);
                hints = Files.readAllLines(path);
            } catch (Exception e) {
                System.out.println("Exception caught: " + e);
                hints = null;
            }
        }
    }

    // put together transcriptMap <wav file bytes,transcription>
    // load transcripts from file if we have them
    private static void transcribeQuestions(RoboJudgeCLIOptions options) {
        try (Stream<Path> questionWavs = Files.list(Paths.get(options.wavDirectory))) {
            // read question files
            questions = questionWavs.filter(Files::isRegularFile)
                    .filter(q -> q.toString().toLowerCase().endsWith("wav"))
                    .map(RoboJudge::readFileBytes)
                    .map(ByteString::copyFrom)
                    .collect(Collectors.toList());

            // load transcriptMap if it exists
            try {
                FileInputStream tmFile = new FileInputStream(TRANSCRIPT_MAP_FILE);
                ObjectInputStream tmIn = new ObjectInputStream(tmFile);
                transcriptMap = (HashMap<Integer,String>) tmIn.readObject();
                tmIn.close();
                tmFile.close();
            } catch (FileNotFoundException f) { }

            // transcribe any question files we haven't seen before
            for (ByteString q : questions) {
                // if we've seen this one, skip it
                if (transcriptMap.containsKey(q.hashCode())) {
                    System.out.println("Seen: " + transcriptMap.get(q.hashCode()));
                    continue;
                }

                // if not, transcribe
                AudioInputStream questionStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(q.toByteArray()));
                int sampleRate = (int) questionStream.getFormat().getSampleRate();

                try(SpeechClient client = SpeechClient.create()) {
                    // build context with hints file
                    SpeechContext speechContext = SpeechContext.getDefaultInstance();
                    if (hints != null) {
                        speechContext = SpeechContext.newBuilder().addAllPhrases(hints).build();
                    }

                    // send request, get response
                    RecognitionConfig config = RecognitionConfig.newBuilder()
                            .setLanguageCode(options.langCode)
                            .setSampleRateHertz(sampleRate)
                            .addSpeechContexts(speechContext)
                            .build();
                    RecognitionAudio audio = RecognitionAudio.newBuilder().setContent(q).build();
                    RecognizeRequest request = RecognizeRequest.newBuilder().setConfig(config).setAudio(audio).build();
                    RecognizeResponse response = client.recognize(request);

                    // concatenate all the lines of the response
                    String tr = "";
                    for (SpeechRecognitionResult result : response.getResultsList()) {
                        tr += result.getAlternatives(0).getTranscript() + " ";
                    }
                    tr = tr.trim();

                    // store it in the map
                    System.out.println("Heard: " + tr);
                    transcriptMap.put(q.hashCode(), tr);
                }
            }

            // save the transcriptMap
            FileOutputStream tmFile = new FileOutputStream(TRANSCRIPT_MAP_FILE);
            ObjectOutputStream tmOut = new ObjectOutputStream(tmFile);
            tmOut.writeObject(transcriptMap);
            tmOut.close();
            tmFile.close();

            //TODO: what about questions we delete?
        } catch (Exception e) {
            System.out.println("Exception caught: " + e);
            System.exit(-1);
        }
    }

    private static void getKeywordsForQuestions() {
        // load keywordMap if it exists
        try {
            FileInputStream kwFile = new FileInputStream(KEYWORD_MAP_FILE);
            ObjectInputStream kwIn = new ObjectInputStream(kwFile);
            keywordMap = (HashMap<String,ArrayList<Integer>>) kwIn.readObject();
            kwIn.close();
            kwFile.close();
        } catch (FileNotFoundException f) {

        } catch (Exception e) {
            System.out.println("Error reading keyword map file.");
            System.out.println(e);
        }

        // add to keywordMap
        for (ByteString q : questions) {
            // print transcript for q
            int qHash = q.hashCode();
            String tr = transcriptMap.get(qHash);
            System.out.println("Question: " + tr);

            // print existing keywords for q
            System.out.println("Keywords: ");
            keywordMap.forEach((keyword,questions) -> { if (questions.contains(qHash)) System.out.println("\t" + keyword); });
            //TODO: what if we want to delete one?

            // get new keywords for q
            System.out.println("Enter new keywords or phrases, one per line, with an empty line to continue:");
            Scanner in = new Scanner(System.in);
            String k = in.nextLine().toLowerCase().trim();
            while (!k.equals("")) {
                ArrayList<Integer> l = keywordMap.computeIfAbsent(k, k1 -> new ArrayList<>());
                if(!l.contains(qHash)) l.add(qHash);
                k = in.nextLine().toLowerCase().trim();
            }
        }

        // save the keywordMap
        try {
            FileOutputStream kwFile = new FileOutputStream(KEYWORD_MAP_FILE);
            ObjectOutputStream kwOut = new ObjectOutputStream(kwFile);
            kwOut.writeObject(keywordMap);
            kwOut.close();
            kwFile.close();
        } catch (Exception e) {
            System.out.println("Error writing keyword map file.");
            System.out.println(e);
        }
    }

    public static void main(String... args) {
        RoboJudgeCLIOptions options = RoboJudgeCLIOptions.fromFlags(args);
        if (options == null) {
            // Could not parse.
            System.out.println("Failed to parse options.");
            System.exit(1);
        }

        parseHints(options);
        transcribeQuestions(options);
        getKeywordsForQuestions();

        // main loop
        /*
        try {
            infiniteStreamingRecognize(options.langCode);
        } catch (Exception e) {
            System.out.println("Exception caught: " + e);
        }
        */
    }

    public static String convertMillisToDate(double milliSeconds) {
        long millis = (long) milliSeconds;
        DecimalFormat format = new DecimalFormat();
        format.setMinimumIntegerDigits(2);
        return String.format(
                "%s:%s /",
                format.format(TimeUnit.MILLISECONDS.toMinutes(millis)),
                format.format(
                        TimeUnit.MILLISECONDS.toSeconds(millis)
                                - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))));
    }

    /** Performs infinite streaming speech recognition */
    public static void infiniteStreamingRecognize(String languageCode) throws Exception {

        // Microphone Input buffering
        class MicBuffer implements Runnable {

            @Override
            public void run() {
                System.out.println(YELLOW);
                System.out.println("Start speaking...Press Ctrl-C to stop");
                targetDataLine.start();
                byte[] data = new byte[BYTES_PER_BUFFER];
                while (targetDataLine.isOpen()) {
                    try {
                        int numBytesRead = targetDataLine.read(data, 0, data.length);
                        if ((numBytesRead <= 0) && (targetDataLine.isOpen())) {
                            continue;
                        }
                        sharedQueue.put(data.clone());
                    } catch (InterruptedException e) {
                        System.out.println("Microphone input buffering interrupted : " + e.getMessage());
                    }
                }
            }
        }

        // Creating microphone input buffer thread
        MicBuffer micrunnable = new MicBuffer();
        Thread micThread = new Thread(micrunnable);
        ResponseObserver<StreamingRecognizeResponse> responseObserver;
        try (SpeechClient client = SpeechClient.create()) {
            ClientStream<StreamingRecognizeRequest> clientStream;
            responseObserver =
                    new ResponseObserver<StreamingRecognizeResponse>() {

                        ArrayList<StreamingRecognitionResult> results = new ArrayList<>();
                        HashMap<String,Integer> seen = new HashMap<>();

                        public void onStart(StreamController controller) {
                            referenceToStreamController = controller;
                        }

                        // This gets called several times for a given "sentence" as the decoder refines its
                        // understanding of words earlier in the stream. So the same words will show up over and over
                        // again until there's a sentence-ending pause. At that point, result.getIsFinal() is true,
                        // and after that the decoder discards the words before that point and doesn't return them
                        // in the result anymore.
                        public void onResponse(StreamingRecognizeResponse response) {
                            StreamingRecognitionResult result = response.getResultsList().get(0);
                            results.add(result);
                            Duration resultEndTime = result.getResultEndTime();
                            resultEndTimeInMS =
                                    (int) ((resultEndTime.getSeconds() * 1000) + (resultEndTime.getNanos() / 1000000));
                            double correctedTime =
                                    resultEndTimeInMS - bridgingOffset + (STREAMING_LIMIT * restartCounter);

                            SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);

                            // showing the transcript
                            if (result.getIsFinal()) {
                                System.out.print(GREEN);
                                System.out.print("\033[2K\r");
                                System.out.printf(
                                        "%s: %s",
                                        convertMillisToDate(correctedTime),
                                        alternative.getTranscript());

                                isFinalEndTime = resultEndTimeInMS;
                                lastTranscriptWasFinal = true;
                            } else {
                                System.out.print(RED);
                                System.out.print("\033[2K\r");
                                System.out.printf(
                                        "%s: %s", convertMillisToDate(correctedTime), alternative.getTranscript());

                                lastTranscriptWasFinal = false;
                            }

                            // recognize keywords
                            String tr = alternative.getTranscript();
                            tr = tr.toLowerCase();

                            for (String k : keywords) {
                                k = k.toLowerCase();

                                // if (we recognize a keyword) AND ((we haven't seen it before) OR (we've seen it before but in another location))
                                if (tr.contains(k) && (!seen.containsKey(k) || seen.get(k) != tr.lastIndexOf(k))) {
                                    seen.put(k, tr.lastIndexOf(k));
                                    System.out.print("\n" + BLUE);
                                    System.out.println("*** Recognized " + k + " ***");
                                }
                            }

                            if (lastTranscriptWasFinal) {
                                seen = new HashMap<>();
                                System.out.println();
                            }
                        }

                        public void onComplete() {}

                        public void onError(Throwable t) {}
                    };
            clientStream = client.streamingRecognizeCallable().splitCall(responseObserver);

            SpeechContext speechContext = SpeechContext.getDefaultInstance();
            if (hints != null) {
                speechContext = SpeechContext.newBuilder().addAllPhrases(hints).build();
            }

            RecognitionConfig recognitionConfig =
                    RecognitionConfig.newBuilder()
                            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                            .setLanguageCode(languageCode)
                            .setSampleRateHertz(16000)
                            .addSpeechContexts(speechContext)
                            .build();

            StreamingRecognitionConfig streamingRecognitionConfig =
                    StreamingRecognitionConfig.newBuilder()
                            .setConfig(recognitionConfig)
                            .setInterimResults(true)
                            .build();

            StreamingRecognizeRequest request =
                    StreamingRecognizeRequest.newBuilder()
                            .setStreamingConfig(streamingRecognitionConfig)
                            .build(); // The first request in a streaming call has to be a config

            clientStream.send(request);

            try {
                // SampleRate:16000Hz, SampleSizeInBits: 16, Number of channels: 1, Signed: true,
                // bigEndian: false
                AudioFormat audioFormat = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info targetInfo =
                        new Info(
                                TargetDataLine.class,
                                audioFormat); // Set the system information to read from the microphone audio
                // stream

                if (!AudioSystem.isLineSupported(targetInfo)) {
                    System.out.println("Microphone not supported");
                    System.exit(0);
                }
                // Target data line captures the audio stream the microphone produces.
                targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
                targetDataLine.open(audioFormat);
                micThread.start();

                long startTime = System.currentTimeMillis();

                while (true) {

                    long estimatedTime = System.currentTimeMillis() - startTime;

                    if (estimatedTime >= STREAMING_LIMIT) {

                        clientStream.closeSend();
                        referenceToStreamController.cancel(); // remove Observer

                        if (resultEndTimeInMS > 0) {
                            finalRequestEndTime = isFinalEndTime;
                        }
                        resultEndTimeInMS = 0;

                        lastAudioInput = null;
                        lastAudioInput = audioInput;
                        audioInput = new ArrayList<>();

                        restartCounter++;

                        if (!lastTranscriptWasFinal) {
                            System.out.print('\n');
                        }

                        newStream = true;

                        clientStream = client.streamingRecognizeCallable().splitCall(responseObserver);

                        request =
                                StreamingRecognizeRequest.newBuilder()
                                        .setStreamingConfig(streamingRecognitionConfig)
                                        .build();

                        System.out.println(YELLOW);
                        System.out.printf("%d: RESTARTING REQUEST\n", restartCounter * STREAMING_LIMIT);

                        startTime = System.currentTimeMillis();

                    } else {

                        if ((newStream) && (lastAudioInput.size() > 0)) {
                            // if this is the first audio from a new request
                            // calculate amount of unfinalized audio from last request
                            // resend the audio to the speech client before incoming audio
                            double chunkTime = STREAMING_LIMIT / lastAudioInput.size();
                            // ms length of each chunk in previous request audio arrayList
                            if (chunkTime != 0) {
                                if (bridgingOffset < 0) {
                                    // bridging Offset accounts for time of resent audio
                                    // calculated from last request
                                    bridgingOffset = 0;
                                }
                                if (bridgingOffset > finalRequestEndTime) {
                                    bridgingOffset = finalRequestEndTime;
                                }
                                int chunksFromMS =
                                        (int) Math.floor((finalRequestEndTime - bridgingOffset) / chunkTime);
                                // chunks from MS is number of chunks to resend
                                bridgingOffset =
                                        (int) Math.floor((lastAudioInput.size() - chunksFromMS) * chunkTime);
                                // set bridging offset for next request
                                for (int i = chunksFromMS; i < lastAudioInput.size(); i++) {
                                    request =
                                            StreamingRecognizeRequest.newBuilder()
                                                    .setAudioContent(lastAudioInput.get(i))
                                                    .build();
                                    clientStream.send(request);
                                }
                            }
                            newStream = false;
                        }

                        tempByteString = ByteString.copyFrom(sharedQueue.take());

                        request =
                                StreamingRecognizeRequest.newBuilder().setAudioContent(tempByteString).build();

                        audioInput.add(tempByteString);
                    }

                    clientStream.send(request);
                }
            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }
}