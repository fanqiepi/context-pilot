-- Unknown historical answer disposition remains NULL; do not classify old prose.
ALTER TABLE chat_message ADD COLUMN refused BOOLEAN;
