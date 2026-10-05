-- The interface moved to a dark, champagne-gold theme. Installations still on the old default teal move to
-- the new default gold; a colour the customer chose themselves is left as it is.
UPDATE app_setting SET brand_color = '#c9a96e' WHERE brand_color = '#0f766e';
