package ru.curs.homework.configuration;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonSerde;
import ru.curs.counting.model.Bet;
import ru.curs.counting.model.EventScore;
import ru.curs.counting.model.Fraud;
import ru.curs.counting.model.Outcome;
import ru.curs.homework.util.ScoreTransformer;

import java.time.Duration;

import static ru.curs.counting.model.TopicNames.BET_TOPIC;
import static ru.curs.counting.model.TopicNames.EVENT_SCORE_TOPIC;

@Configuration
@RequiredArgsConstructor
public class TopologyConfiguration {
    public static final String BETTOR_AMOUNTS = "bettor-amounts";
    public static final String TEAM_AMOUNTS = "team-amounts";
    public static final String POSSIBLE_FRAUDS = "possible-frauds";

    @Bean
    public Topology createTopology(StreamsBuilder streamsBuilder) {

        /*
        Необходимо создать топологию, которая имеет следующие три выходных топика:
           -- таблица, ключом которой является имя игрока,
           а значением -- сумма ставок, произведённых игроком
           -- таблица, ключом которой является имя команды,
            а значением -- сумма ставок на эту команду (ставки на "ничью" в подсчёте игнорируются)
           -- поток, ключом которого является имя игрока,
           а значениями -- подозрительные ставки.
           Подозрительными считаем ставки, произведённые в пользу команды
           в пределах одной секунды до забития этой командой гола.
         */
        KStream<String, Bet> bets = streamsBuilder.stream(BET_TOPIC,
            Consumed
                .with(Serdes.String(), new JsonSerde<>(Bet.class))
                .withTimestampExtractor((record, previousTimestamp) -> ((Bet) record.value()).getTimestamp())
        );

        KTable<String, Long> bettorAmounts = bets
            .map((k, v) -> KeyValue.pair(v.getBettor(), v.getAmount()))
            .groupByKey(Grouped.with(Serdes.String(), new JsonSerde<>(Long.class)))
            .reduce(Long::sum);

        KTable<String, Long> teamAmounts = bets
            .filter((k, v) -> v.getOutcome() != Outcome.D)
            .map((k, v) -> KeyValue.pair(
                v.getMatch().split("-")[v.getOutcome() == Outcome.H ? 0 : 1],
                v.getAmount())
            )
            .groupByKey(Grouped.with(Serdes.String(), new JsonSerde<>(Long.class)))
            .reduce(Long::sum);

        KStream<String, EventScore> eventScores = streamsBuilder.stream(EVENT_SCORE_TOPIC,
            Consumed
                .with(Serdes.String(), new JsonSerde<>(EventScore.class))
                .withTimestampExtractor((record, previousTimestamp) -> ((EventScore) record.value()).getTimestamp())
        );

        KStream<String, Bet> wins = new ScoreTransformer().transformStream(streamsBuilder, eventScores);
        KStream<String, Fraud> possibleFrauds = bets
            .join(
                wins,
                (bet, winBet) -> Fraud
                    .builder()
                    .bettor(bet.getBettor())
                    .outcome(bet.getOutcome())
                    .amount(bet.getAmount())
                    .match(bet.getMatch())
                    .odds(bet.getOdds())
                    .lag(winBet.getTimestamp() - bet.getTimestamp())
                    .build(),
                JoinWindows.of(Duration.ofSeconds(1)).before(Duration.ZERO),
                StreamJoined.with(Serdes.String(), new JsonSerde<>(Bet.class), new JsonSerde<>(Bet.class))
            )
            .selectKey((k, v) -> v.getBettor());

        bettorAmounts.toStream().to(BETTOR_AMOUNTS, Produced.with(Serdes.String(), Serdes.Long()));
        teamAmounts.toStream().to(TEAM_AMOUNTS, Produced.with(Serdes.String(), Serdes.Long()));
        possibleFrauds.to(POSSIBLE_FRAUDS, Produced.with(Serdes.String(), new JsonSerde<>(Fraud.class)));

        Topology topology = streamsBuilder.build();
        System.out.println("==============================");
        System.out.println(topology.describe());
        System.out.println("==============================");
        // https://zz85.github.io/kafka-streams-viz/
        return topology;
    }
}
