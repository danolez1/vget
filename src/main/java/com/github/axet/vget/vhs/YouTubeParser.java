package com.github.axet.vget.vhs;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import com.github.axet.vget.info.VGetParser;
import com.github.axet.vget.info.VideoInfo;
import com.github.axet.vget.info.VideoInfo.States;
import com.github.axet.vget.vhs.YoutubeInfo.YoutubeQuality;
import com.github.axet.wget.WGet;
import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.ex.DownloadError;
import com.github.axet.wget.info.ex.DownloadRetry;

public class YouTubeParser extends VGetParser {

    static public class VideoDownload {
        public YoutubeQuality vq;
        public URL url;

        public VideoDownload(YoutubeQuality vq, URL u) {
            this.vq = vq;
            this.url = u;
        }
    }

    static public class VideoContentFirst implements Comparator<VideoDownload> {

        @Override
        public int compare(VideoDownload o1, VideoDownload o2) {
            Integer i1 = o1.vq.ordinal();
            Integer i2 = o2.vq.ordinal();
            Integer ic = i1.compareTo(i2);

            return ic;
        }

    }

    final static String UTF8 = "UTF-8";

    static class DecryptSignature {
        String sig;

        public DecryptSignature(String signature) {
            this.sig = signature;
        }

        String s(int b, int e) {
            return sig.substring(b, e);
        }

        String s(int b) {
            return sig.substring(b, b + 1);
        }

        String se(int b) {
            return s(b, sig.length());
        }

        String s(int b, int e, int step) {
            String str = "";

            while (b != e) {
                str += sig.charAt(b);
                b += step;
            }
            return str;
        }

        // https://github.com/rg3/youtube-dl/blob/master/youtube_dl/extractor/youtube.py
        String decrypt() {
            switch (sig.length()) {
            case 93:
                return s(86, 29, -1) + s(88) + s(28, 5, -1);
            case 92:
                return s(25) + s(3, 25) + s(0) + s(26, 42) + s(79) + s(43, 79) + s(91) + s(80, 83);
            case 91:
                return s(84, 27, -1) + s(86) + s(26, 5, -1);
            case 90:
                return s(25) + s(3, 25) + s(2) + s(26, 40) + s(77) + s(41, 77) + s(89) + s(78, 81);
            case 89:
                return s(84, 78, -1) + s(87) + s(77, 60, -1) + s(0) + s(59, 3, -1);
            case 88:
                return s(7, 28) + s(87) + s(29, 45) + s(55) + s(46, 55) + s(2) + s(56, 87) + s(28);
            case 87:
                return s(6, 27) + s(4) + s(28, 39) + s(27) + s(40, 59) + s(2) + se(60);
            case 86:
                return s(80, 72, -1) + s(16) + s(71, 39, -1) + s(72) + s(38, 16, -1) + s(82) + s(15, 0, -1);
            case 85:
                return s(3, 11) + s(0) + s(12, 55) + s(84) + s(56, 84);
            case 84:
                return s(78, 70, -1) + s(14) + s(69, 37, -1) + s(70) + s(36, 14, -1) + s(80) + s(0, 14, -1);
            case 83:
                return s(80, 63, -1) + s(0) + s(62, 0, -1) + s(63);
            case 82:
                return s(80, 37, -1) + s(7) + s(36, 7, -1) + s(0) + s(6, 0, -1) + s(37);
            case 81:
                return s(56) + s(79, 56, -1) + s(41) + s(55, 41, -1) + s(80) + s(40, 34, -1) + s(0) + s(33, 29, -1)
                        + s(34) + s(28, 9, -1) + s(29) + s(8, 0, -1) + s(9);
            case 80:
                return s(1, 19) + s(0) + s(20, 68) + s(19) + s(69, 80);
            case 79:
                return s(54) + s(77, 54, -1) + s(39) + s(53, 39, -1) + s(78) + s(38, 34, -1) + s(0) + s(33, 29, -1)
                        + s(34) + s(28, 9, -1) + s(29) + s(8, 0, -1) + s(9);
            }

            throw new RuntimeException("Unable to decrypt signature, key length " + sig.length()
                    + " not supported; retrying might work");
        }
    }

    public static class VideoUnavailablePlayer extends DownloadError {
        private static final long serialVersionUID = 10905065542230199L;

        public VideoUnavailablePlayer() {
            super("unavailable-player");
        }
    }

    public static class AgeException extends DownloadError {
        private static final long serialVersionUID = 1L;

        public AgeException() {
            super("Age restriction, account required");
        }
    }

    public static class PrivateVideoException extends DownloadError {
        private static final long serialVersionUID = 1L;

        public PrivateVideoException() {
            super("Private video");
        }

        public PrivateVideoException(String s) {
            super(s);
        }
    }

    public static class EmbeddingDisabled extends DownloadError {
        private static final long serialVersionUID = 1L;

        public EmbeddingDisabled(String msg) {
            super(msg);
        }
    }

    public static class VideoDeleted extends DownloadError {
        private static final long serialVersionUID = 1L;

        public VideoDeleted(String msg) {
            super(msg);
        }
    }

    public YouTubeParser() {
    }

    public static boolean probe(URL url) {
        return url.toString().contains("youtube.com");
    }

    public List<VideoDownload> extractLinks(final VideoInfo info) {
        return extractLinks(info, new AtomicBoolean(), new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    public List<VideoDownload> extractLinks(final VideoInfo info, final AtomicBoolean stop, final Runnable notify) {
        try {
            try {
                return extractEmbedded(info, stop, notify);
            } catch (EmbeddingDisabled e) {
                return streamCpature(info, stop, notify);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * do not allow to download age restricted videos
     * 
     * @param info
     * @param stop
     * @param notify
     * @throws Exception
     */
    List<VideoDownload> streamCpature(final VideoInfo info, final AtomicBoolean stop, final Runnable notify)
            throws Exception {
        List<VideoDownload> sNextVideoURL = new ArrayList<VideoDownload>();

        String html;
        html = WGet.getHtml(info.getWeb(), new WGet.HtmlLoader() {
            @Override
            public void notifyRetry(int delay, Throwable e) {
                info.setDelay(delay, e);
                notify.run();
            }

            @Override
            public void notifyDownloading() {
                info.setState(States.DOWNLOADING);
                notify.run();
            }

            @Override
            public void notifyMoved() {
                info.setState(States.RETRYING);
                notify.run();
            }
        }, stop);
        extractHtmlInfo(sNextVideoURL, info, html, stop, notify);
        extractIcon(info, html);

        return sNextVideoURL;
    }

    /**
     * Add resolution video for specific youtube link.
     * 
     * @param url
     *            download source url
     * @throws MalformedURLException
     */
    void addVideo(List<VideoDownload> sNextVideoURL, String itag, URL url) {
        Integer i = Integer.decode(itag);
        YoutubeQuality vd = itagMap.get(i);

        sNextVideoURL.add(new VideoDownload(vd, url));
    }

    // http://en.wikipedia.org/wiki/YouTube#Quality_and_codecs

    static final Map<Integer, YoutubeQuality> itagMap = new HashMap<Integer, YoutubeQuality>() {
        private static final long serialVersionUID = -6925194111122038477L;
        {
            put(120, YoutubeQuality.p720); // flv
            put(102, YoutubeQuality.p720); // webm
            put(101, YoutubeQuality.p360); // webm
            put(100, YoutubeQuality.p360); // webm
            put(85, YoutubeQuality.p520); // mp4
            put(84, YoutubeQuality.p720); // mp4
            put(83, YoutubeQuality.p240); // mp4
            put(82, YoutubeQuality.p360); // mp4
            put(46, YoutubeQuality.p1080); // webm
            put(45, YoutubeQuality.p720); // webm
            put(44, YoutubeQuality.p480); // webm
            put(43, YoutubeQuality.p360); // webm
            put(38, YoutubeQuality.p3072); // mp4
            put(37, YoutubeQuality.p1080); // mp4
            put(36, YoutubeQuality.p240); // 3gp
            put(35, YoutubeQuality.p480); // flv
            put(34, YoutubeQuality.p360); // flv
            put(22, YoutubeQuality.p720); // mp4
            put(18, YoutubeQuality.p360); // mp4
            put(17, YoutubeQuality.p144); // 3gp
            put(6, YoutubeQuality.p270); // flv
            put(5, YoutubeQuality.p240); // flv
        }
    };

    public static String extractId(URL url) {
        {
            Pattern u = Pattern.compile("youtube.com/watch?.*v=([^&]*)");
            Matcher um = u.matcher(url.toString());
            if (um.find())
                return um.group(1);
        }

        {
            Pattern u = Pattern.compile("youtube.com/v/([^&]*)");
            Matcher um = u.matcher(url.toString());
            if (um.find())
                return um.group(1);
        }

        return null;
    }

    /**
     * allows to download age restricted videos
     * 
     * @param info
     * @param stop
     * @param notify
     * @throws Exception
     */
    List<VideoDownload> extractEmbedded(final VideoInfo info, final AtomicBoolean stop, final Runnable notify)
            throws Exception {
        List<VideoDownload> sNextVideoURL = new ArrayList<VideoDownload>();

        String id = extractId(info.getWeb());
        if (id == null) {
            throw new RuntimeException("unknown url");
        }

        info.setTitle(String.format("http://www.youtube.com/watch?v=%s", id));

        String get = String.format("http://www.youtube.com/get_video_info?authuser=0&video_id=%s&el=embedded", id);

        URL url = new URL(get);

        String qs = WGet.getHtml(url, new WGet.HtmlLoader() {
            @Override
            public void notifyRetry(int delay, Throwable e) {
                info.setDelay(delay, e);
                notify.run();
            }

            @Override
            public void notifyDownloading() {
                info.setState(States.DOWNLOADING);
                notify.run();
            }

            @Override
            public void notifyMoved() {
                info.setState(States.RETRYING);
                notify.run();
            }
        }, stop);

        Map<String, String> map = getQueryMap(qs);

        if (map.get("status").equals("fail")) {
            String r = URLDecoder.decode(map.get("reason"), UTF8);
            if (map.get("errorcode").equals("150"))
                throw new EmbeddingDisabled("error code 150");
            if (map.get("errorcode").equals("100"))
                throw new VideoDeleted("error code 100");

            throw new DownloadError(r);
            // throw new PrivateVideoException(r);
        }

        info.setTitle(URLDecoder.decode(map.get("title"), UTF8));

        // String fmt_list = URLDecoder.decode(map.get("fmt_list"), UTF8);
        // String[] fmts = fmt_list.split(",");

        String url_encoded_fmt_stream_map = URLDecoder.decode(map.get("url_encoded_fmt_stream_map"), UTF8);

        extractUrlEncodedVideos(sNextVideoURL, url_encoded_fmt_stream_map);

        // 'iurlmaxresæ or 'iurlsd' or 'thumbnail_url'
        String icon = map.get("thumbnail_url");
        icon = URLDecoder.decode(icon, UTF8);
        info.setIcon(new URL(icon));

        return sNextVideoURL;
    }

    void extractIcon(VideoInfo info, String html) {
        try {
            Pattern title = Pattern.compile("itemprop=\"thumbnailUrl\" href=\"(.*)\"");
            Matcher titleMatch = title.matcher(html);
            if (titleMatch.find()) {
                String sline = titleMatch.group(1);
                sline = StringEscapeUtils.unescapeHtml4(sline);
                info.setIcon(new URL(sline));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, String> getQueryMap(String qs) {
        try {
            qs = qs.trim();
            List<NameValuePair> list;
            list = URLEncodedUtils.parse(new URI(null, null, null, -1, null, qs, null), UTF8);
            HashMap<String, String> map = new HashMap<String, String>();
            for (NameValuePair p : list) {
                map.put(p.getName(), p.getValue());
            }
            return map;
        } catch (URISyntaxException e) {
            throw new RuntimeException(qs, e);
        }
    }

    void extractHtmlInfo(List<VideoDownload> sNextVideoURL, VideoInfo info, String html, AtomicBoolean stop,
            Runnable notify) throws Exception {
        {
            Pattern age = Pattern.compile("(verify_age)");
            Matcher ageMatch = age.matcher(html);
            if (ageMatch.find())
                throw new AgeException();
        }

        {
            Pattern age = Pattern.compile("(unavailable-player)");
            Matcher ageMatch = age.matcher(html);
            if (ageMatch.find())
                throw new VideoUnavailablePlayer();
        }

        {
            Pattern urlencod = Pattern.compile("\"url_encoded_fmt_stream_map\": \"([^\"]*)\"");
            Matcher urlencodMatch = urlencod.matcher(html);
            if (urlencodMatch.find()) {
                String url_encoded_fmt_stream_map;
                url_encoded_fmt_stream_map = urlencodMatch.group(1);

                // normal embedded video, unable to grab age restricted videos
                Pattern encod = Pattern.compile("url=(.*)");
                Matcher encodMatch = encod.matcher(url_encoded_fmt_stream_map);
                if (encodMatch.find()) {
                    String sline = encodMatch.group(1);

                    extractUrlEncodedVideos(sNextVideoURL, sline);
                }

                // stream video
                Pattern encodStream = Pattern.compile("stream=(.*)");
                Matcher encodStreamMatch = encodStream.matcher(url_encoded_fmt_stream_map);
                if (encodStreamMatch.find()) {
                    String sline = encodStreamMatch.group(1);

                    String[] urlStrings = sline.split("stream=");

                    for (String urlString : urlStrings) {
                        urlString = StringEscapeUtils.unescapeJava(urlString);

                        Pattern link = Pattern.compile("(sparams.*)&itag=(\\d+)&.*&conn=rtmpe(.*),");
                        Matcher linkMatch = link.matcher(urlString);
                        if (linkMatch.find()) {

                            String sparams = linkMatch.group(1);
                            String itag = linkMatch.group(2);
                            String url = linkMatch.group(3);

                            url = "http" + url + "?" + sparams;

                            url = URLDecoder.decode(url, UTF8);

                            addVideo(sNextVideoURL, itag, new URL(url));
                        }
                    }
                }
            }
        }

        {
            Pattern title = Pattern.compile("<meta name=\"title\" content=(.*)");
            Matcher titleMatch = title.matcher(html);
            if (titleMatch.find()) {
                String sline = titleMatch.group(1);
                String name = sline.replaceFirst("<meta name=\"title\" content=", "").trim();
                name = StringUtils.strip(name, "\">");
                name = StringEscapeUtils.unescapeHtml4(name);
                info.setTitle(name);
            }
        }
    }

    void extractUrlEncodedVideos(List<VideoDownload> sNextVideoURL, String sline) throws Exception {
        String[] urlStrings = sline.split("url=");

        for (String urlString : urlStrings) {
            urlString = StringEscapeUtils.unescapeJava(urlString);

            String urlFull = URLDecoder.decode(urlString, UTF8);

            // universal request
            {
                String url = null;
                {
                    Pattern link = Pattern.compile("([^&,]*)[&,]");
                    Matcher linkMatch = link.matcher(urlString);
                    if (linkMatch.find()) {
                        url = linkMatch.group(1);
                        url = URLDecoder.decode(url, UTF8);
                    }
                }

                String itag = null;
                {
                    Pattern link = Pattern.compile("itag=(\\d+)");
                    Matcher linkMatch = link.matcher(urlFull);
                    if (linkMatch.find()) {
                        itag = linkMatch.group(1);
                    }
                }

                String sig = null;

                if (sig == null) {
                    Pattern link = Pattern.compile("&signature=([^&,]*)");
                    Matcher linkMatch = link.matcher(urlFull);
                    if (linkMatch.find()) {
                        sig = linkMatch.group(1);
                    }
                }

                if (sig == null) {
                    Pattern link = Pattern.compile("sig=([^&,]*)");
                    Matcher linkMatch = link.matcher(urlFull);
                    if (linkMatch.find()) {
                        sig = linkMatch.group(1);
                    }
                }

                if (sig == null) {
                    Pattern link = Pattern.compile("[&,]s=([^&,]*)");
                    Matcher linkMatch = link.matcher(urlFull);
                    if (linkMatch.find()) {
                        sig = linkMatch.group(1);

                        DecryptSignature ss = new DecryptSignature(sig);
                        sig = ss.decrypt();
                    }
                }

                if (url != null && itag != null && sig != null) {
                    try {
                        url += "&signature=" + sig;

                        addVideo(sNextVideoURL, itag, new URL(url));
                        continue;
                    } catch (MalformedURLException e) {
                        // ignore bad urls
                    }
                }
            }
        }
    }

    @Override
    public DownloadInfo extract(VideoInfo vinfo, AtomicBoolean stop, Runnable notify) {
        List<VideoDownload> sNextVideoURL = extractLinks(vinfo, stop, notify);

        if (sNextVideoURL.size() == 0) {
            // rare error:
            //
            // The live recording you're trying to play is still being processed
            // and will be available soon. Sorry, please try again later.
            //
            // retry. since youtube may already rendrered propertly quality.
            throw new DownloadRetry("empty video download list," + " wait until youtube will process the video");
        }

        Collections.sort(sNextVideoURL, new VideoContentFirst());

        for (int i = 0; i < sNextVideoURL.size();) {
            VideoDownload v = sNextVideoURL.get(i);

            YoutubeInfo yinfo = (YoutubeInfo) vinfo;
            yinfo.setVideoQuality(v.vq);
            DownloadInfo info = new DownloadInfo(v.url);
            vinfo.setInfo(info);
            return info;
        }

        // throw download stop if user choice not maximum quality and we have no
        // video rendered by youtube

        throw new DownloadError("no video with required quality found,"
                + " increace VideoInfo.setVq to the maximum and retry download");
    }

    @Override
    public VideoInfo info(URL web) {
        return new YoutubeInfo(web);
    }
}
