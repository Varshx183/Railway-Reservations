-- Fresh schema for the repaired project. Original databases are not modified.
CREATE TABLE IF NOT EXISTS train_services (
    train_id integer NOT NULL CHECK (train_id > 0),
    journey_date date NOT NULL,
    PRIMARY KEY (train_id, journey_date)
);

CREATE TABLE IF NOT EXISTS seat_inventory (
    train_id integer NOT NULL,
    journey_date date NOT NULL,
    travel_class varchar(2) NOT NULL CHECK (travel_class IN ('AC', 'SL')),
    coach_count integer NOT NULL CHECK (coach_count BETWEEN 0 AND 100),
    seats_per_coach integer NOT NULL,
    seats_booked integer NOT NULL DEFAULT 0,
    PRIMARY KEY (train_id, journey_date, travel_class),
    FOREIGN KEY (train_id, journey_date) REFERENCES train_services,
    CHECK ((travel_class = 'AC' AND seats_per_coach = 18) OR
           (travel_class = 'SL' AND seats_per_coach = 24)),
    CHECK (seats_booked BETWEEN 0 AND coach_count * seats_per_coach)
);

CREATE TABLE IF NOT EXISTS tickets (
    pnr uuid PRIMARY KEY,
    train_id integer NOT NULL,
    journey_date date NOT NULL,
    travel_class varchar(2) NOT NULL,
    passenger_count integer NOT NULL CHECK (passenger_count BETWEEN 1 AND 100),
    booked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (train_id, journey_date, travel_class) REFERENCES seat_inventory,
    UNIQUE (pnr, train_id, journey_date, travel_class)
);

CREATE TABLE IF NOT EXISTS passengers (
    pnr uuid NOT NULL,
    passenger_order integer NOT NULL CHECK (passenger_order > 0),
    passenger_name varchar(100) NOT NULL CHECK (length(btrim(passenger_name)) > 0),
    train_id integer NOT NULL,
    journey_date date NOT NULL,
    travel_class varchar(2) NOT NULL,
    seat_index integer NOT NULL CHECK (seat_index >= 0),
    coach_number integer NOT NULL CHECK (coach_number > 0),
    berth_number integer NOT NULL CHECK (berth_number > 0),
    berth_type varchar(2) NOT NULL CHECK (berth_type IN ('LB', 'MB', 'UB', 'SL', 'SU')),
    PRIMARY KEY (pnr, passenger_order),
    FOREIGN KEY (pnr, train_id, journey_date, travel_class)
      REFERENCES tickets(pnr, train_id, journey_date, travel_class),
    UNIQUE (train_id, journey_date, travel_class, seat_index)
);

-- Monday = 0, Sunday = 6. Arrival offset is relative to departure date.
CREATE TABLE IF NOT EXISTS routes (
    train_id integer NOT NULL CHECK (train_id > 0),
    source varchar(100) NOT NULL CHECK (length(btrim(source)) > 0),
    destination varchar(100) NOT NULL CHECK (length(btrim(destination)) > 0),
    departure_time time NOT NULL,
    arrival_time time NOT NULL,
    departure_day integer NOT NULL CHECK (departure_day BETWEEN 0 AND 6),
    arrival_offset_days integer NOT NULL CHECK (arrival_offset_days BETWEEN 0 AND 6),
    PRIMARY KEY (train_id, source, destination, departure_day),
    CHECK (source <> destination),
    CHECK (arrival_offset_days > 0 OR arrival_time > departure_time)
);
CREATE INDEX IF NOT EXISTS routes_source_destination ON routes(lower(source), lower(destination));

CREATE OR REPLACE FUNCTION release_train(p_train integer, p_date date, p_ac integer, p_sl integer)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    IF p_train IS NULL OR p_train <= 0 OR p_date IS NULL OR p_ac IS NULL OR p_sl IS NULL
       OR p_ac NOT BETWEEN 0 AND 100 OR p_sl NOT BETWEEN 0 AND 100 OR p_ac + p_sl = 0 THEN
        RAISE EXCEPTION 'INVALID_REQUEST' USING ERRCODE = '22023';
    END IF;
    INSERT INTO train_services VALUES (p_train, p_date) ON CONFLICT DO NOTHING;
    IF NOT FOUND THEN RETURN false; END IF;
    INSERT INTO seat_inventory VALUES
        (p_train, p_date, 'AC', p_ac, 18, 0), (p_train, p_date, 'SL', p_sl, 24, 0);
    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION book_tickets(p_train integer, p_date date, p_class varchar, p_names text[])
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE
    inventory seat_inventory%ROWTYPE;
    count_names integer := cardinality(p_names);
    ticket_pnr uuid := gen_random_uuid();
    idx integer;
    ordinal integer := 0;
    berth integer;
    kind varchar(2);
    name text;
BEGIN
    IF p_train IS NULL OR p_train <= 0 OR p_date IS NULL OR p_class IS NULL
       OR p_class NOT IN ('AC', 'SL') OR count_names IS NULL OR count_names NOT BETWEEN 1 AND 100
       OR array_ndims(p_names) <> 1 THEN
        RAISE EXCEPTION 'INVALID_REQUEST' USING ERRCODE = '22023';
    END IF;
    FOREACH name IN ARRAY p_names LOOP
        IF name IS NULL OR length(btrim(name)) NOT BETWEEN 1 AND 100 THEN
            RAISE EXCEPTION 'INVALID_REQUEST' USING ERRCODE = '22023';
        END IF;
    END LOOP;
    -- Requests for this inventory serialize; different train/date/classes do not.
    SELECT * INTO inventory FROM seat_inventory
      WHERE train_id = p_train AND journey_date = p_date AND travel_class = p_class FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'TRAIN_NOT_AVAILABLE' USING ERRCODE = 'P0001'; END IF;
    IF inventory.coach_count * inventory.seats_per_coach - inventory.seats_booked < count_names THEN
        RAISE EXCEPTION 'SEATS_NOT_AVAILABLE' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO tickets(pnr, train_id, journey_date, travel_class, passenger_count)
      VALUES(ticket_pnr, p_train, p_date, p_class, count_names);
    FOREACH name IN ARRAY p_names LOOP
        idx := inventory.seats_booked + ordinal;
        berth := idx % inventory.seats_per_coach + 1;
        IF p_class = 'AC' THEN
            kind := (ARRAY['LB','LB','UB','UB','SL','SU'])[((berth - 1) % 6) + 1];
        ELSE
            kind := (ARRAY['LB','MB','UB','LB','MB','UB','SL','SU'])[((berth - 1) % 8) + 1];
        END IF;
        ordinal := ordinal + 1;
        INSERT INTO passengers VALUES(ticket_pnr, ordinal, btrim(name), p_train, p_date,
            p_class, idx, idx / inventory.seats_per_coach + 1, berth, kind);
    END LOOP;
    UPDATE seat_inventory SET seats_booked = seats_booked + count_names
      WHERE train_id = p_train AND journey_date = p_date AND travel_class = p_class;
    RETURN ticket_pnr;
END;
$$;

CREATE OR REPLACE FUNCTION search_routes(p_source text, p_destination text)
RETURNS jsonb LANGUAGE sql STABLE AS $$
WITH segments AS (
    SELECT r.*,
      departure_day * 1440 + extract(epoch FROM departure_time) / 60 AS dep,
      (departure_day + arrival_offset_days) * 1440 + extract(epoch FROM arrival_time) / 60 AS arr
    FROM routes r
), journeys AS (
    SELECT a.dep AS sort_time, a.train_id AS sort_train,
      jsonb_build_object('type', 'DIRECT', 'train', a.train_id, 'source', a.source,
        'destination', a.destination, 'departureDay', a.departure_day,
        'departureTime', a.departure_time, 'arrivalDay', (a.departure_day + a.arrival_offset_days) % 7,
        'arrivalTime', a.arrival_time, 'durationMinutes', a.arr - a.dep) AS result
    FROM segments a WHERE lower(a.source) = lower(btrim(p_source))
      AND lower(a.destination) = lower(btrim(p_destination))
    UNION ALL
    SELECT a.dep, a.train_id,
      jsonb_build_object('type', 'ONE_TRANSFER', 'train1', a.train_id, 'train2', b.train_id,
        'source', a.source, 'destination', b.destination, 'transferStation', a.destination,
        'departureDay', a.departure_day, 'departureTime', a.departure_time,
        'transferArrivalDay', (a.departure_day + a.arrival_offset_days) % 7,
        'transferArrivalTime', a.arrival_time, 'transferDepartureDay', b.departure_day,
        'transferDepartureTime', b.departure_time,
        'arrivalDay', (b.departure_day + b.arrival_offset_days) % 7, 'arrivalTime', b.arrival_time,
        'waitMinutes', wait.minutes, 'durationMinutes', a.arr - a.dep + wait.minutes + b.arr - b.dep)
    FROM segments a JOIN segments b ON lower(a.destination) = lower(b.source) AND a.train_id <> b.train_id
    CROSS JOIN LATERAL (SELECT mod(mod(b.dep - a.arr, 10080) + 10080, 10080) AS minutes) wait
    WHERE lower(a.source) = lower(btrim(p_source)) AND lower(b.destination) = lower(btrim(p_destination))
      AND wait.minutes > 0 AND wait.minutes < 120
)
SELECT coalesce(jsonb_agg(result ORDER BY sort_time, sort_train, result::text), '[]'::jsonb) FROM journeys;
$$;
