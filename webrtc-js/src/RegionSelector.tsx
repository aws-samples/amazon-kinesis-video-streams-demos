import React from 'react';
import {Col, Form, Row} from 'react-bootstrap';

interface RegionSelectorProps {
    regionChanged: (updatedRegion: string) => void;
}

interface RegionSelectorState {
    regionsList: React.ReactElement[] | null;
    selectedRegion: string;
}

class RegionSelector extends React.Component<RegionSelectorProps, RegionSelectorState> {
    constructor(props: RegionSelectorProps) {
        super(props);

        this.state = {
            regionsList: null,
            selectedRegion: localStorage.getItem('region') || '',
        }
    }

    async componentDidMount() {
        const res = await fetch('https://api.regional-table.region-services.aws.a2z.com/index.jsons')
        if (!res.ok) {
            alert(`Error fetching regions! ${res.status}: ${res.statusText}`);
            return;
        }
        const regionData = await res.json();

        let key = 0;
        const regions: React.ReactElement[] = [<option key={-1} value="">Choose a region...</option>, regionData?.prices
            ?.filter((serviceData: any) => serviceData?.attributes['aws:serviceName'] === 'Amazon Kinesis Video Streams')
            .map((kinesisVideoServiceData: any) => kinesisVideoServiceData?.attributes['aws:region'])
            .sort()
            .map((region: any) => {
                return <option value={region} key={key++}>{region}</option>;
            })];

        this.setState({regionsList: regions})
    }

    onRegionSelected = (event: React.FormEvent<HTMLSelectElement>) => {
        // @ts-ignore
        this.props.regionChanged(event.target.value);
        this.setState((oldState) => {
            return {
                ...oldState,
                // @ts-ignore
                selectedRegion: event.target.value
            }
        })
    }

    render() {
        return (
            <Form>
                <Form.Group as={Row} className="mb-3" controlId="Region">
                    <Form.Label column lg="2">Region</Form.Label>
                    <Col lg={true}>
                        <Form.Select aria-label="Region selector" onChange={this.onRegionSelected} value={this.state.selectedRegion}>
                            {this.state.regionsList}
                        </Form.Select>
                    </Col>
                </Form.Group>
            </Form>
        )
    }
}

export default RegionSelector;
