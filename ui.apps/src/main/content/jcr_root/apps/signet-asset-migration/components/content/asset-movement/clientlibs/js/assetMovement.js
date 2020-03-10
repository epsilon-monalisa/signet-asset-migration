function postData()
{
	var action=$('#action').val();
	var sourceFol=$('#coral-2').val();;
	var destFol=$('#coral-4').val();;
	
	$.ajax({
		
		url:'/bin/signetWorflowTrigger',
		data:{"source":sourceFol,"destn":destFol,"action":action},
		type:'POST',
		error:function(){alert('Error in Moving !!');}
	});

}