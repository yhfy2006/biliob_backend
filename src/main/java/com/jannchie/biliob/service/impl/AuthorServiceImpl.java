package com.jannchie.biliob.service.impl;

import com.jannchie.biliob.constant.AuthorSortEnum;
import com.jannchie.biliob.constant.PageSizeEnum;
import com.jannchie.biliob.exception.AuthorAlreadyFocusedException;
import com.jannchie.biliob.exception.UserAlreadyFavoriteAuthorException;
import com.jannchie.biliob.model.Author;
import com.jannchie.biliob.model.RealTimeFans;
import com.jannchie.biliob.repository.AuthorRepository;
import com.jannchie.biliob.repository.RealTimeFansRepository;
import com.jannchie.biliob.service.AuthorService;
import com.jannchie.biliob.service.SiteService;
import com.jannchie.biliob.service.UserService;
import com.jannchie.biliob.utils.BiliOBUtils;
import com.jannchie.biliob.utils.InputInspection;
import com.jannchie.biliob.utils.MySlice;
import com.jannchie.biliob.utils.RedisOps;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Projections;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;

import static com.mongodb.client.model.Aggregates.*;
import static com.mongodb.client.model.Sorts.descending;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * @author jannchie
 */
@Service
@CacheConfig(cacheNames = "authorService")
public class AuthorServiceImpl implements AuthorService {
    private static final Logger logger = LogManager.getLogger(VideoServiceImpl.class);
    private final RedisOps redisOps;
    private final AuthorRepository respository;
    private final RealTimeFansRepository realTimeFansRepository;
    private final MongoTemplate mongoTemplate;
    private final UserService userService;
    private final SiteService siteService;
    private MongoClient mongoClient;

    @Autowired
    public AuthorServiceImpl(
            AuthorRepository respository,
            UserService userService,
            MongoClient mongoClient,
            MongoTemplate mongoTemplate,
            InputInspection inputInspection,
            RealTimeFansRepository realTimeFansRepository,
            RedisOps redisOps,
            SiteService siteService) {
        this.respository = respository;
        this.userService = userService;
        this.mongoTemplate = mongoTemplate;
        this.mongoClient = mongoClient;
        this.realTimeFansRepository = realTimeFansRepository;
        this.redisOps = redisOps;
        this.siteService = siteService;
    }

    @Override
    public Author getAggregatedData(Long mid) {
        Aggregation a =
                Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("mid").is(mid)),
                        Aggregation.unwind("$data"),
                        Aggregation.project()
                                .andExpression("year($data.datetime)")
                                .as("year")
                                .andExpression("month($data.datetime)")
                                .as("month")
                                .andExpression("dayOfMonth($data.datetime)")
                                .as("day")
                                .andInclude(
                                        "data",
                                        "mid",
                                        "name",
                                        "face",
                                        "sex",
                                        "official",
                                        "level",
                                        "channels",
                                        "rank",
                                        "focus",
                                        "forceFocus",
                                        "cRate",
                                        "cFans",
                                        "cArchiveView",
                                        "cArticleView"),
                        Aggregation.group(
                                "year",
                                "month",
                                "day",
                                "mid",
                                "name",
                                "face",
                                "sex",
                                "official",
                                "level",
                                "channels",
                                "rank",
                                "focus",
                                "forceFocus",
                                "cRate",
                                "cFans",
                                "cArchiveView",
                                "cArticleView")
                                .max("data")
                                .as("data"),
                        Aggregation.sort(Sort.Direction.DESC, "year", "month", "day"),
                        Aggregation.group(
                                "mid",
                                "name",
                                "face",
                                "sex",
                                "official",
                                "level",
                                "channels",
                                "rank",
                                "focus",
                                "forceFocus",
                                "cRate",
                                "cFans",
                                "cArchiveView",
                                "cArticleView")
                                .push("data")
                                .as("data"));
        return mongoTemplate.aggregate(a, "author", Author.class).getMappedResults().get(0);
    }

    @Override
    public Author getAuthorDetails(Long mid, Integer type) {
        addAuthorVisit(mid);
        if (type == 1) {
            return getAggregatedData(mid);
        }
        Query query = new Query(where("mid").is(mid));
        query.fields().exclude("fansRate");
        return mongoTemplate.findOne(query, Author.class, "author");
    }

    private void addAuthorVisit(Long mid) {
        String finalUserName = BiliOBUtils.getUserName();
        Map data = BiliOBUtils.getVisitData(finalUserName, mid);
        AuthorServiceImpl.logger.info("用户[{}]查询mid[{}]的详细数据", finalUserName, mid);
        mongoTemplate.insert(data, "author_visit");
    }

    @Override
    public void postAuthorByMid(Long mid)
            throws AuthorAlreadyFocusedException, UserAlreadyFavoriteAuthorException {
        userService.addFavoriteAuthor(mid);
        AuthorServiceImpl.logger.info(mid);
        if (respository.findByMid(mid) != null) {
            throw new AuthorAlreadyFocusedException(mid);
        }
        redisOps.postAuthorCrawlTask(mid);
        respository.save(new Author(mid));
    }


    @Override
    @Cacheable(value = "author_slice", key = "#mid + #text + #page + #pagesize + #sort")
    public MySlice<Author> getAuthor(
            Long mid, String text, Integer page, Integer pagesize, Integer sort) {
        if (pagesize > PageSizeEnum.BIG_SIZE.getValue()) {
            pagesize = PageSizeEnum.BIG_SIZE.getValue();
        }
        String sortKey = AuthorSortEnum.getKeyByFlag(sort);
        if (!(mid == -1)) {
            AuthorServiceImpl.logger.info(mid);
            return new MySlice<>(
                    respository.searchByMid(
                            mid, PageRequest.of(page, pagesize, new Sort(Sort.Direction.DESC, sortKey))));
        } else if (!Objects.equals(text, "")) {
            AuthorServiceImpl.logger.info(text);
            if (InputInspection.isId(text)) {
                // get a mid
                return new MySlice<>(
                        respository.searchByMid(
                                Long.valueOf(text),
                                PageRequest.of(page, pagesize, new Sort(Sort.Direction.DESC, sortKey))));
            }
            // get text
            String[] textArray = text.split(" ");
            MySlice<Author> mySlice =
                    new MySlice<>(
                            respository.findByKeywordContaining(
                                    textArray,
                                    PageRequest.of(page, pagesize, new Sort(Sort.Direction.DESC, sortKey))));
            if (mySlice.getContent().isEmpty()) {
                for (String eachText : textArray) {
                    HashMap<String, String> map = new HashMap<>(1);
                    map.put("mid", eachText);
                    mongoTemplate.insert(map, "search_word");
                }
            }
            return mySlice;
        } else {
            AuthorServiceImpl.logger.info("查看所有UP主列表");
            return new MySlice<>(
                    respository.findAllByDataIsNotNull(
                            PageRequest.of(page, pagesize, new Sort(Sort.Direction.DESC, sortKey))));
        }
    }

    /**
     * get a list of author's fans increase rate.
     *
     * @return list of author rate of fans increase.
     */
    @Override
    public ResponseEntity listFansIncreaseRate() {
        Slice<Author> slice =
                respository.listTopIncreaseRate(
                        PageRequest.of(0, 20, new Sort(Sort.Direction.DESC, "cRate")));
        AuthorServiceImpl.logger.info("获得涨粉榜");
        return new ResponseEntity<>(slice, HttpStatus.OK);
    }

    /**
     * get a list of author's fans decrease rate.
     *
     * @return list of author rate of fans decrease.
     */
    @Override
    public ResponseEntity listFansDecreaseRate() {
        Slice<Author> slice =
                respository.listTopIncreaseRate(
                        PageRequest.of(0, 20, new Sort(Sort.Direction.ASC, "cRate")));
        AuthorServiceImpl.logger.info("获得掉粉榜");
        return new ResponseEntity<>(slice, HttpStatus.OK);
    }

    @Override
    public ResponseEntity getTopAuthor() {
        Calendar c = Calendar.getInstance();
        c.setTimeZone(TimeZone.getTimeZone("CTT"));
        c.add(Calendar.HOUR, 7);
        Date cDate = c.getTime();
        AggregateIterable<Document> r = mongoClient.getDatabase("biliob").
                getCollection("author").
                aggregate(Arrays.asList(
                        sort(descending("cFans")),
                        limit(2),
                        project(Projections.fields(Projections.excludeId(),
                                Projections.include("name", "face", "official"),
                                Projections.computed("data", new Document()
                                        .append("$filter", new Document()
                                                .append("input", "$data").append("as", "eachData").append("cond", new Document()
                                                        .append("$gt", Arrays.asList("$$eachData.datetime", cDate))))))
                        )));
        ArrayList<Document> result = new ArrayList<>(2);
        for (Document document : r) {
            result.add(document);
        }

        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity getLatestTopAuthorData() {
        AggregateIterable<Document> r = mongoClient.getDatabase("biliob").
                getCollection("author").
                aggregate(Arrays.asList(
                        sort(descending("cFans")),
                        limit(2),
                        project(Projections.fields(
                                Projections.excludeId(),
                                Projections.include("name", "face", "official"),
                                Projections.computed("data", new Document("$slice", Arrays.asList("$data", 1))))
                        )));
        ArrayList<Document> result = new ArrayList<>(2);
        for (Document document : r) {
            result.add(document);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * get specific author's fans rate
     *
     * @param mid author id
     * @return list of fans
     */
    @Override
    public ResponseEntity listFansRate(Long mid) {
        Author author = respository.getFansRate(mid);
        List data = author.getFansRate();
        AuthorServiceImpl.logger.info("获得粉丝变动率");
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    /**
     * get author information exclude history data.
     *
     * @param mid author id
     * @return author
     */
    @Override
    public Author getAuthorInfo(Long mid) {
        return respository.findAuthorByMid(mid);
    }

    /**
     * list real time data
     *
     * @param aMid one author id
     * @param bMid another author id
     * @return Real time fans responseEntity
     */
    @Override
    public ResponseEntity getRealTimeData(Long aMid, Long bMid) {
        Calendar c = Calendar.getInstance();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("GMT+:00:00"));

        List<RealTimeFans> aRealTimeFans = listRealTimeFans(aMid);
        ArrayList<Integer> aFans = new ArrayList<>();
        ArrayList<String> datetime = new ArrayList<>();
        for (RealTimeFans item : aRealTimeFans) {
            c.setTime(item.getDatetime());
            datetime.add(format.format(c.getTime()));
            aFans.add(item.getFans());
        }

        List<RealTimeFans> bRealTimeFans = listRealTimeFans(bMid);
        ArrayList<Integer> bFans = new ArrayList<>();
        for (RealTimeFans item : bRealTimeFans) {
            bFans.add(item.getFans());
        }

        HashMap<String, Cloneable> result = new HashMap<>(3);
        result.put("aFans", aFans);
        result.put("bFans", bFans);
        result.put("datetime", datetime);

        return new ResponseEntity<>(result, HttpStatus.OK);
    }


    private List<RealTimeFans> listRealTimeFans(Long mid) {
        return realTimeFansRepository.findTop180ByMidOrderByDatetimeDesc(mid);
    }

    /**
     * list author tag
     *
     * @param mid author id
     * @return tag list
     */
    @Override
    public List<Map> listAuthorTag(Long mid, Integer limit) {
        Aggregation a =
                Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("mid").is(mid)),
                        Aggregation.unwind("tag"),
                        Aggregation.project("tag", "cView"),
                        Aggregation.group("tag").sum("cView").as("totalView").count().as("count"),
                        Aggregation.sort(Sort.Direction.DESC, "count"),
                        Aggregation.limit(limit));
        return mongoTemplate.aggregate(a, "video", Map.class).getMappedResults();
    }

    /**
     * list relate author by author id
     *
     * @param mid   author id
     * @param limit length of result list
     * @return author list
     */
    @Override
    @Cacheable(value = "relate_author", key = "#mid + #limit")
    public List listRelatedAuthorByMid(Long mid, Integer limit) {
        int tagLimit = limit;
        List<Map> tagMap = listAuthorTag(mid, 5);
        List cList = new ArrayList<>();
        for (Map item : tagMap) {
            cList.add(item.get("_id"));
        }
        if (cList.size() == 0) {
            return cList;
        }
        List<Map> result = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -3);

        Aggregation a =
                Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("mid").is(mid)),
                        Aggregation.lookup("author", "mid", "mid", "authorDoc"),
                        Aggregation.unwind("tag"),
                        Aggregation.group("mid")
                                .count()
                                .as("count")
                                .avg("cView")
                                .as("value")
                                .last("author")
                                .as("name")
                                .addToSet("tag")
                                .as("tag")
                                .last("authorDoc.face")
                                .as("face"),
                        Aggregation.unwind("face"),
                        Aggregation.sort(Sort.Direction.DESC, "value"));
        Map host = mongoTemplate.aggregate(a, "video", Map.class).getUniqueMappedResult();
        while (result.size() <= 6) {
            List hostTag = (List) (host != null ? host.get("tag") : null);

            Aggregation b =
                    Aggregation.newAggregation(
                            Aggregation.match(
                                    Criteria.where("datetime")
                                            .gt(c.getTime())
                                            .and("tag")
                                            .all(cList)
                                            .and("mid")
                                            .ne(mid)),
                            Aggregation.limit(5000),
                            Aggregation.lookup("author", "mid", "mid", "authorDoc"),
                            Aggregation.unwind("tag"),
                            Aggregation.group("mid")
                                    .count()
                                    .as("count")
                                    .avg("cView")
                                    .as("value")
                                    .last("author")
                                    .as("name")
                                    .addToSet("tag")
                                    .as("tag")
                                    .last("authorDoc.face")
                                    .as("face"),
                            Aggregation.unwind("face"),
                            Aggregation.sort(Sort.Direction.DESC, "value"),
                            Aggregation.limit(20));

            for (Map item : mongoTemplate.aggregate(b, "video", Map.class).getMappedResults()) {
                Boolean flag = false;
                for (Map resultItem : result) {
                    if (item.get("_id").equals(resultItem.get("_id"))) {
                        flag = true;
                    }
                }
                if (!flag) {
                    List<String> tempTagList = new ArrayList<>();
                    for (String tag : (List<String>) item.get("tag")) {
                        if (hostTag != null && hostTag.contains(tag)) {
                            tempTagList.add(tag);
                        }
                    }
                    item.put("tag", tempTagList);
                    result.add(item);
                }
            }
            if (cList.size() <= 1) {
                break;
            }
            cList.remove(cList.size() - 1);
        }
        result.add(host);
        return result;
    }
}
