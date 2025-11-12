cp3: cp3_control.c
	gcc -o cp3_control cp3_control.c -lpigpiod_if2 -lpthread -lrt

ir: ir_test.c
	gcc -o ir_test ir_test.c -lpigpiod_if2 -lpthread -lrt

parmco: parmco_server.c
	gcc -o parmco_server parmco_server.c -lpigpiod_if2 -lpthread -lrt -lbluetooth