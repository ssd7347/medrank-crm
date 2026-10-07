-- Claude Sonnet 5.5 is now the default brain: smarter, and it caches the script from the second turn.
UPDATE voice_script SET model = 'claude-sonnet-5-5' WHERE model = 'claude-haiku-4-5';
