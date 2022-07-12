var argscheck = require('cordova/argscheck');
var channel = require('cordova/channel');
var exec = require('cordova/exec');
var cordova = require('cordova');

/**
 * @constructor
 */
function CameraPreview2(){

}

CameraPreview2.prototype.tmp=function(action, successCallback, errorCallback){
	exec(successCallback, errorCallback, 'CameraPreview2', action);
};

module.exports = new CameraPreview2();