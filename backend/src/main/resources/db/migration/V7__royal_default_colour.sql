-- The default look moves from champagne gold to royal sapphire; customers who chose their own colour keep it.
UPDATE app_setting SET brand_color = '#2b3f9e' WHERE brand_color = '#c9a96e';
