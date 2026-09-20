-- Do not discard subscription history to repair pre-existing duplicates.
DO $$
BEGIN
    IF EXISTS (
        SELECT restaurant_id
        FROM restaurant_subscriptions
        WHERE status = 'PENDING'
        GROUP BY restaurant_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V8: duplicate PENDING subscriptions exist for a restaurant. Resolve them explicitly before retrying migration; no data was deleted.'
            USING ERRCODE = '23505';
    END IF;
END $$;

CREATE UNIQUE INDEX uq_restaurant_subscriptions_one_pending
    ON restaurant_subscriptions (restaurant_id)
    WHERE status = 'PENDING';
