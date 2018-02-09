package com.ftpix.homedash.plugins.couchpotato.apis;

import com.ftpix.homedash.plugins.couchpotato.models.ImagePath;
import com.ftpix.homedash.plugins.couchpotato.models.MovieObject;
import com.ftpix.homedash.plugins.couchpotato.models.MoviesRootFolder;
import com.ftpix.homedash.plugins.couchpotato.models.QualityProfile;
import com.google.common.io.Files;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.imgscalr.Scalr;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ftpix.homedash.plugins.couchpotato.CouchPotatoPlugin.THUMB_SIZE;

public class RadarrApi extends MovieProviderAPI {
    private Logger logger = LogManager.getLogger();
    private final String baseUrl, url, apiKey;
    private final ImagePath imagePath;

    private final String API_MOVIE_SEARCH = "movies/lookup";
    private final String API_ADD_MOVIE = "movie.add/?title=[TITLE]&identifier=[IMDB]";
    private final String API_AVAILABLE = "system/status";
    private final String API_PROFILES = "profile";
    private final String API_ROOT_FOLDERS = "rootfolder";
    private final String API_MOVIE_LIST = "movie";

    public RadarrApi(String baseUrl, String apiKey, ImagePath imagePath) {
        super(baseUrl, apiKey, imagePath);
        this.baseUrl = baseUrl;
        this.imagePath = imagePath;

        url = baseUrl + "api/%s?apikey=" + apiKey;
        this.apiKey = apiKey;
    }

    @Override
    public String getRandomWantedPoster() throws Exception {

        JSONArray movies = new JSONArray(Unirest.get(String.format(url, API_MOVIE_LIST)).asString().getBody());
        String poster = null;
        List<String> posters = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) {

            JSONObject movieInfo = movies.getJSONObject(i);
            JSONArray images = movieInfo.getJSONArray("images");
            if (images.length() != 0) {
                String imageUrl = null;
                for (int j = 0; j < images.length(); j++) {
                    JSONObject imageObject = images.getJSONObject(j);
                    if (imageObject.getString("coverType").equalsIgnoreCase("poster")) {
                        posters.add(imageObject.getString("url"));
                    }
                }

                // poster = images.getString(new

            }
        }

        Collections.shuffle(posters);
        if (posters.size() > 0) {
            String selected = posters.get(0);
            String fileName = imagePath.get() + selected.replaceAll("[^a-zA-Z0-9]", "") + ".jpg";
            File f = new File(fileName);
            if (!f.exists()) {
                String finalUrl = String.format(url, selected);
                FileUtils.copyURLToFile(new java.net.URL(finalUrl), f);
                BufferedImage image = ImageIO.read(f);
                BufferedImage resized = Scalr.resize(image, THUMB_SIZE);
                ImageIO.write(resized, Files.getFileExtension(f.getName()), f);
            }
            poster = fileName;

        }

        // Random().nextInt(images.length()));

        return poster;
    }

    @Override
    public Map<String, String> validateSettings() {
        Map<String, String> errors = new Hashtable<String, String>();
        String url = String.format(this.url, API_AVAILABLE);
        try {
            Unirest.get(url).asString().getBody();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            logger.info("Can't access Radarr at URL [{}]", url);
            errors.put("Unavailable", "Radarr is not available at this URL: " + url);
        }

        return errors;
    }

    @Override
    public void addMovie(MovieObject movie) throws UnsupportedEncodingException, UnirestException {

    }

    @Override
    public List<MovieObject> searchMovie(String query) throws Exception {
        String fullUrl = String.format(url, API_MOVIE_SEARCH) + "&term=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String body = Unirest.get(fullUrl).asString().getBody();
        JSONArray movies = new JSONArray(body);
        List<MovieObject> moviesResults = new ArrayList<>();
        List<String> postersToDownload = new ArrayList<>();


        for (int i = 0; i < movies.length(); i++) {
            JSONObject movie = movies.getJSONObject(i);
            MovieObject movieObject = new MovieObject();
            movieObject.rawJson = movie.toString();
            Optional.ofNullable(movie.getString("title")).ifPresent(s -> movieObject.originalTitle = s);
            Optional.ofNullable(movie.getInt("year")).ifPresent(s -> movieObject.year = s);
            Optional.ofNullable(movie.getInt("tmdbId")).ifPresent(s -> movieObject.imdbId = Integer.toString(s));
            Optional.ofNullable(movie.getBoolean("hasFile")).ifPresent(s -> movieObject.inLibrary = s);
            Optional.ofNullable(movie.getBoolean("monitored")).ifPresent(s -> movieObject.wanted = s);

            JSONArray images = movie.getJSONArray("images");
            for (int j = 0; j < images.length(); j++) {

                JSONObject imageObject = images.getJSONObject(j);
                if (imageObject.getString("coverType").equalsIgnoreCase("poster")) {
                    movieObject.poster = imageObject.getString("url");
                    break;
                }
            }

            moviesResults.add(movieObject);
        }

        List<Callable<Void>> downloads = new ArrayList<>();
        moviesResults.forEach(m -> downloads.add(() -> {
            try {
                String fileName = imagePath.get() + m.poster.replaceAll("[^a-zA-Z0-9]", "") + ".jpg";
                File f = new File(fileName);
                if (!f.exists()) {
                    FileUtils.copyURLToFile(new java.net.URL(m.poster), f);
                    BufferedImage image = ImageIO.read(f);
                    BufferedImage resized = Scalr.resize(image, THUMB_SIZE);
                    ImageIO.write(resized, Files.getFileExtension(f.getName()), f);
                }

                m.poster = f.getPath();

            } catch (Exception e) {
                logger.info("Couldn't download movie poster at url: [{}]", m.poster);
            }
            return null;
        }));

        ExecutorService exec = Executors.newFixedThreadPool(moviesResults.size());
        exec.invokeAll(downloads);


        return moviesResults;
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public List<QualityProfile> getQualityProfiles() throws Exception {
        List<QualityProfile> profiles = new ArrayList<>();
        String body = Unirest.get(String.format(url, API_PROFILES)).asString().getBody();
        JSONArray profilesJson = new JSONArray(body);
        for (int i = 0; i < profilesJson.length(); i++) {
            JSONObject profileJson = profilesJson.getJSONObject(i);
            QualityProfile quality = new QualityProfile();
            quality.setName(profileJson.getString("name"));
            quality.setId(profileJson.getInt("id"));

            profiles.add(quality);
        }

        return profiles;
    }

    @Override
    public List<MoviesRootFolder> getMoviesRootFolder() throws Exception {
        List<MoviesRootFolder> folders = new ArrayList<>();
        JSONArray foldersJson = new JSONArray(Unirest.get(String.format(url, API_ROOT_FOLDERS)).asString().getBody());
        for (int i = 0; i < foldersJson.length(); i++) {
            JSONObject folderJson = foldersJson.getJSONObject(i);
            MoviesRootFolder folder = new MoviesRootFolder();
            folder.setName(folderJson.getString("path"));
            folder.setId(folderJson.getInt("id"));

            folders.add(folder);
        }

        return folders;
    }
}
