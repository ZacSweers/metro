// Compose UI's JS klib reads skiko's callback API from the './skiko.mjs' module.
// The JS box runner loads this file before the compiled test, so this stub stands in for that module.
globalThis['./skiko.mjs'] = {
    skikoApi: {
        _createLocalCallbackScope: function() {},
        _releaseLocalCallbackScope: function() {},
        _registerCallback: function(callback) { return callback; },
    },
};
