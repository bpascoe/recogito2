define([
  'document/annotation/common/editor/sections/place/cards/baseCard','common/config',
  'common/api','document/annotation/common/page/header','common/flagstrap',
  'common/ui/countries','document/annotation/common/editor/sections/place/placeSection'], function(Card, Config, API, Header,Flagstrap,Countries,PlaceSection) {
  
  var NoMatchCard = function(containerEl, verificationStatus, lastModified) {
    var header = new Header();
    // TODO cover the case of yellow place status'es - different message + place overlay

    var addPlace = jQuery('<div class="wrapper wrapper--w680 add-place"><div class="card card-1"><div class="card-body">' +
            '<b class="title">Create Place </b><span style="font-size:12px; color:gray;">Fileds with * are required</span>'+
            '<div class="input-group"><input class="input--style-1 title2" type="text" placeholder="Place Name*" value="'+$('#main .selection').text()+'" required></div>'+
            '<div class="input-group"><input class="input--style-1 uri" type="text" placeholder="URI"></div>'+
            '<div class="input-group"><input class="input--style-1 latitude" type="text" placeholder="Latitude*" required></div>'+
            '<div class="input-group"><input class="input--style-1 longitude" type="text" placeholder="Longitude*" required></div>'+
            '<div class="input-group"><div class="rs-select2 js-select-simple select--no-search">'+
             '<select name="country"><option disabled="disabled" selected="selected">Country</option></select>'+
             '<div class="select-dropdown"></div></div></div>'+
            '<div class="input-group"><input class="input--style-1 description" type="text" placeholder="Description"></div>'+
            '<div class="input-group"><input class="input--style-1 js-datepicker from" type="text" placeholder="Timespan Start yyyy/mm/dd"><i class="zmdi zmdi-calendar-note input-icon js-btn-calendar"></i></div>'+
            '<div class="input-group"><input class="input--style-1 js-datepicker2 to" type="text" placeholder="Timespan End yyyy/mm/dd"><i class="zmdi zmdi-calendar-note input-icon js-btn-calendar2"></i></div>'+
            '<div class="input-group"><input class="input--style-1 altNames" type="text" placeholder="Alternate Names"></div>'+
            '<div class="p-t-20"><button class="btn btn--radius btn--red btn-cancel-place">Cancel</button>'+
            '<button class="btn btn--radius btn--green add-place-submit">Submit</button></div>'+
            '</div></div></div>'+
            // '<div><button class="btn-add-place">Create Place</button></div>' +
          '</div>'),
        // addButton = jQuery('<button class="btn tiny toggle" style="z-index: 3;position: fixed;margin-top: -24px;">Add palce manually</button>'),
        element = (Config.writeAccess) ? jQuery(
          '<div class="info-text">' +
            '<div class="no-match">' +
              '<div class="label"></div>' +
              '<button class="btn tiny change" title="Use advanced search to find a match">Search</button> ' +
              '<button class="btn tiny flag icon" title="Flag this place as unidentified">&#xf11d;</button> ' +
              '<button class="btn tiny delete icon" title="Not a place - remove">&#xf014;</button>' +
              '<button class="btn tiny btn-add-place" title="Create place" style="margin-left:2px;">Create Place</button>' +
            '</div>' +
          '</div>') : jQuery(
          // Simplified version for read-only mode
          '<div class="info-text">' +
            '<div class="no-match readonly">' +
              '<div class="label"></div>' +
            '</div>' +
          '</div>'),
        // toggle = element.find('.toggle'),

        labelEl = element.find('.label'),

        btnFlag = element.find('.flag'),

        labelNoAutoMatch =
          '<h3>No automatic match found</h3>',

        labelFlagged =
          '<h3>Flagged as Not Identifiable</h3>' +
          '<p>A place no-one could resolve yet</p>',

        overlayFlagged =
          '<div class="map-overlay flagged">' +
            '<span class="icon">&#xe842;</span>' +
          '</div>',

        render = function() {
          containerEl.html(element);
          // containerEl.after(addButton);
          // $("#main").after(addPlace);
          if (verificationStatus && verificationStatus.value === 'NOT_IDENTIFIABLE')
            setFlagged();
          else
            labelEl.html(labelNoAutoMatch);
        },

        setFlagged = function() {
          labelEl.html(labelFlagged);
          btnFlag.remove();
          containerEl.append(overlayFlagged);
        };
        $.each(Countries.getCountries() , function(index, val) {
          var options = addPlace.find('select').prop('options');
          options[options.length] = new Option(val, index);
        });
    this.render = render;
    this.setFlagged = setFlagged;

    Card.apply(this, [ element ]);

    render();

    element.on('click', '.btn-add-place', function() {
      // addPlace.find(".page-wrapper").get(0).style.display = "block";
      
      // $(".annotation-editor-popup-inner .cancel").click();
      $(".annotation-editor-popup").hide();
      $("#main").after(addPlace);
      $(".add-place" ).dialog();
      // datepicker
      $(".from" ).datepicker({
        forceParse: false,
        dateFormat: 'yy/mm/dd',
        changeMonth: true,
        changeYear: true,
        showOn: "button",
        buttonImage: "https://jqueryui.com/resources/demos/datepicker/images/calendar.gif",
        buttonImageOnly: true,
        buttonText: "Select date"
      });
      $(".to" ).datepicker({
        forceParse: false,
        dateFormat: 'yy/mm/dd',
        changeMonth: true,
        changeYear: true,
        showOn: "button",
        buttonImage: "https://jqueryui.com/resources/demos/datepicker/images/calendar.gif",
        buttonImageOnly: true,
        buttonText: "Select date"
      });
    });
    $('.add-place').dialog({autoOpen: false,title: 'Create Place'});

    addPlace.on('click', '.add-place-submit', function() {
      var title = addPlace.find(".title2").val(),
          uri = addPlace.find(".uri").val(),
          lat = addPlace.find(".latitude").val(),
          lon = addPlace.find(".longitude").val(),
          from = addPlace.find(".from").val(),
          to = addPlace.find(".to").val(),
          ccode = addPlace.find("#country").val(),
          altNames = addPlace.find(".altNames").val(),
          description = addPlace.find(".description").val(),
          jsonData = {'title':title, 'uri': uri, 'lat':parseFloat(lat), 'lon':parseFloat(lon), 'ccode': ccode, 'from': parseInt(from), 'to': parseInt(to),'description':description,'altNames':altNames};
      if (title && lat && lon)
        API.addPlace2Gazetter(jsonData).done(function(result) {
         if (result) {$(".ui-dialog").remove();$('.ok').click();} 
         //header.showStatusSaved();
        }).fail(function(error) {
         header.showSaveError(error);
        });
      else 
        alert("All information should be filled")
      });
    
    addPlace.on( "click", ".btn-cancel-place", function() {
      $(".ui-dialog").remove();
      $(".annotation-editor-popup").show();
    });
    $(document).mouseup(function(e){
      var container = $(".ui-dialog");
      if(!container.is(e.target) && container.has(e.target).length === 0){
          if (!$("#ui-datepicker-div").is(e.target) && $("#ui-datepicker-div").has(e.target).length === 0)
          container.hide();
          // $(".annotation-editor-popup").show();
      }
    });
  };
  NoMatchCard.prototype = Object.create(Card.prototype);

  return NoMatchCard;

});
